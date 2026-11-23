const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const { normalizeProfileId } = require("./messaging-store");
const { storageToken } = require("./blob-access");

const ACCOUNTS_PATH = "focus-study/web-accounts.json";
const SYNC_PREFIX = "focus-study/web-sync-";
const LOCAL_DIR = path.join(__dirname, "..", ".data");
const ACCESS = "private";
const MAX_ACCOUNTS = 1000;
const MAX_RECORDS = 2000;
const MAX_TITLE = 200;
const SALT_BYTES = 16;
const KEY_BYTES = 64;
const PASSWORD_MIN = 10;
const PASSWORD_MAX = 200;

const TASK_CATEGORIES = new Set(["study", "university", "personal", "work", "habits", "other"]);
const TASK_STATUSES = new Set(["todo", "doing", "done", "postponed", "cancelled"]);
const RECURRENCES = new Set(["none", "daily", "weekly", "monthly"]);
const COLLECTIONS = ["tasks", "subjects", "taskTypes"];

const cut = (value, max) => String(value ?? "").trim().slice(0, max);

/**
 * Only these fields cross the wire. In particular `notes` (the private notebook and task notes),
 * install ids, device ids, push tokens, display names and secrets are never accepted here.
 */
function cleanRecord(item, kind) {
  if (!item || typeof item !== "object" || Array.isArray(item)) return null;
  const id = cut(item.id, 100);
  if (!id) return null;
  const record = { id, updatedAt: cleanTime(item.updatedAt) };
  // Stored subjects/types use `name` after their first normalization; accept both so re-reading
  // and merging an existing snapshot cannot silently drop the label.
  const title = cut(item.title ?? item.name, MAX_TITLE);
  if (title) record[kind === "subjects" || kind === "taskTypes" ? "name" : "title"] = title;

  if (kind === "tasks") {
    const minutes = Number(item.minutes);
    if (Number.isInteger(minutes) && minutes >= 1 && minutes <= 720) record.minutes = minutes;
    const category = cut(item.category, 24).toLowerCase();
    if (TASK_CATEGORIES.has(category)) record.category = category;
    const priority = Number(item.priority);
    if (Number.isInteger(priority) && priority >= 0 && priority <= 3) record.priority = priority;
    const status = cut(item.status, 24).toLowerCase();
    if (TASK_STATUSES.has(status)) record.status = status;
    const dueAt = Number(item.dueAt);
    if (Number.isSafeInteger(dueAt) && dueAt >= 0) record.dueAt = dueAt;
    const recurrence = cut(item.recurrence, 24).toLowerCase();
    if (RECURRENCES.has(recurrence)) record.recurrence = recurrence;
    for (const key of ["subjectId", "typeId"]) {
      const value = cut(item[key], 100);
      if (value) record[key] = value;
    }
  }
  if (kind === "taskTypes" && item.archived === true) record.archived = true;
  if (item.deleted === true) record.deleted = true;
  return record;
}

function cleanTime(value) {
  const time = Number(value);
  return Number.isSafeInteger(time) && time > 0 ? time : 0;
}

/** Whitelists and de-duplicates a client payload before it ever reaches Blob storage. */
function normalizeSyncPayload(input) {
  const data = {};
  for (const kind of COLLECTIONS) {
    const source = input && Array.isArray(input[kind]) ? input[kind] : [];
    const byId = new Map();
    for (const item of source.slice(0, MAX_RECORDS * 2)) {
      const record = cleanRecord(item, kind);
      if (!record) continue;
      const previous = byId.get(record.id);
      if (!previous || record.updatedAt >= previous.updatedAt) byId.set(record.id, record);
    }
    data[kind] = sortedRecords(byId);
  }
  return data;
}

function sortedRecords(byId) {
  return [...byId.values()]
    .sort((a, b) => b.updatedAt - a.updatedAt || a.id.localeCompare(b.id))
    .slice(0, MAX_RECORDS);
}

/**
 * Per-record last-write-wins. An equal timestamp keeps the stored row so two devices cannot
 * produce a different result depending on which one speaks last.
 */
function mergeSync(existing, incoming, now) {
  const previous = existing && typeof existing === "object" ? existing : {};
  const stored = normalizeSyncPayload(previous.data || {});
  const proposed = normalizeSyncPayload(incoming);
  const data = {};
  for (const kind of COLLECTIONS) {
    const byId = new Map(stored[kind].map((record) => [record.id, record]));
    for (const record of proposed[kind]) {
      const current = byId.get(record.id);
      if (!current || record.updatedAt > current.updatedAt) byId.set(record.id, record);
    }
    data[kind] = sortedRecords(byId);
  }
  return {
    revision: (cleanTime(previous.revision) || 0) + 1,
    updatedAt: cleanTime(now) || Date.now(),
    data,
  };
}

function validatePassphrase(value) {
  const text = String(value ?? "");
  return text.trim().length >= PASSWORD_MIN && text.length <= PASSWORD_MAX ? text : "";
}

function hashPassphrase(passphrase) {
  const salt = crypto.randomBytes(SALT_BYTES).toString("base64");
  const hash = crypto.scryptSync(passphrase, Buffer.from(salt, "base64"), KEY_BYTES).toString("base64");
  return { salt, hash };
}

function verifyPassphrase(passphrase, salt, hash) {
  if (!salt || !hash) return false;
  try {
    const expected = Buffer.from(String(hash), "base64");
    if (!expected.length) return false;
    const actual = crypto.scryptSync(passphrase, Buffer.from(String(salt), "base64"), expected.length);
    return crypto.timingSafeEqual(expected, actual);
  } catch {
    return false;
  }
}

async function readBlobJson(blobPath) {
  const { get } = await import("@vercel/blob");
  const result = await get(blobPath, { access: ACCESS, token: storageToken(ACCESS), useCache: false });
  if (result?.statusCode === 200 && result.stream) {
    return JSON.parse(await new Response(result.stream).text());
  }
  return null;
}

function readLocalJson(blobPath, fallback) {
  try {
    return JSON.parse(fs.readFileSync(path.join(LOCAL_DIR, path.basename(blobPath)), "utf8"));
  } catch {
    return fallback;
  }
}

/** A failed private read throws; it must never fall back to an empty record and overwrite real data. */
async function readJson(blobPath, fallback) {
  if (storageToken(ACCESS)) {
    try {
      const record = await readBlobJson(blobPath);
      if (record) return record;
    } catch (error) {
      console.error("sync store read failed", blobPath, error?.message || "unknown");
      throw new Error("sync_store_unavailable");
    }
  }
  return readLocalJson(blobPath, fallback);
}

async function writeJson(blobPath, value) {
  const token = storageToken(ACCESS);
  if (token) {
    const { put } = await import("@vercel/blob");
    await put(blobPath, JSON.stringify(value), {
      access: ACCESS,
      token,
      addRandomSuffix: false,
      allowOverwrite: true,
      contentType: "application/json; charset=utf-8",
      cacheControlMaxAge: 0,
    });
    return;
  }
  fs.mkdirSync(LOCAL_DIR, { recursive: true });
  fs.writeFileSync(path.join(LOCAL_DIR, path.basename(blobPath)), JSON.stringify(value), "utf8");
}

async function readAccounts() {
  const record = await readJson(ACCOUNTS_PATH, { accounts: [] });
  return Array.isArray(record.accounts) ? record.accounts : [];
}

async function hasAccount(profileId) {
  const accounts = await readAccounts();
  return accounts.some((item) => item.profileId === profileId);
}

async function setAccount(profileId, passphrase) {
  const accounts = await readAccounts();
  const { salt, hash } = hashPassphrase(passphrase);
  const now = Date.now();
  const record = { profileId, salt, hash, createdAt: now, updatedAt: now };
  const next = accounts.some((item) => item.profileId === profileId)
    ? accounts.map((item) => (item.profileId === profileId ? { ...item, salt, hash, updatedAt: now } : item))
    : [record, ...accounts];
  await writeJson(ACCOUNTS_PATH, { accounts: next.slice(0, MAX_ACCOUNTS) });
}

async function authenticate(profileId, passphrase) {
  const accounts = await readAccounts();
  const account = accounts.find((item) => item.profileId === profileId);
  // Spend the same work on a missing account so the response cannot be timed apart from a wrong password.
  if (!account) {
    hashPassphrase(passphrase);
    return false;
  }
  return verifyPassphrase(passphrase, account.salt, account.hash);
}

const syncPathFor = (profileId) => `${SYNC_PREFIX}${profileId}.json`;

async function readSync(profileId) {
  const record = await readJson(syncPathFor(profileId), { revision: 0, updatedAt: 0, data: {} });
  return {
    revision: cleanTime(record.revision),
    updatedAt: cleanTime(record.updatedAt),
    data: normalizeSyncPayload(record.data || {}),
  };
}

async function writeSync(profileId, record) {
  await writeJson(syncPathFor(profileId), record);
}

module.exports = {
  COLLECTIONS, PASSWORD_MIN, PASSWORD_MAX, MAX_RECORDS,
  normalizeProfileId, validatePassphrase, hashPassphrase, verifyPassphrase,
  normalizeSyncPayload, mergeSync, readAccounts, hasAccount, setAccount,
  authenticate, readSync, writeSync,
};
