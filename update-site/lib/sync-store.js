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
// H1: a v2 encrypted envelope is base64(12-byte IV + ciphertext + 16-byte tag);
// the largest synced record stays far below this cap.
const MAX_ENC_CHARS = 8192;

const TASK_CATEGORIES = new Set(["study", "university", "personal", "work", "habits", "other"]);
const TASK_STATUSES = new Set(["todo", "doing", "done", "postponed", "cancelled"]);
const RECURRENCES = new Set(["none", "daily", "weekly", "monthly"]);
const MONEY_KINDS = new Set(["income", "expense"]);
const DAY_PATTERN = /^\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$/;
// «حياتي» collections (windows-sync contract §2–§3): habits + habit_logs, wird_logs,
// health_logs (+ calorie_logs), transactions (money), mood_logs. They ride the same
// generic normalizeSyncPayload/mergeSync loop as tasks/subjects/taskTypes.
const COLLECTIONS = [
  "tasks", "subjects", "taskTypes",
  "habits", "habitLogs", "wirdLogs", "healthLogs", "transactions", "moodLogs", "calorieLogs",
];

const cut = (value, max) => String(value ?? "").trim().slice(0, max);

/**
 * Only these fields cross the wire. In particular `notes` (the private notebook, task notes and
 * money-transaction notes), install ids, device ids, push tokens, display names and secrets are
 * never accepted here. Shapes are fixed by the windows-sync contract (§3); a field not listed
 * there is dropped silently, never stored.
 */
function cleanRecord(item, kind) {
  if (!item || typeof item !== "object" || Array.isArray(item)) return null;
  const id = cut(item.id, 100);
  // habitLogs use a derived id ("habitId:day"); accept a missing id there and derive it
  // before the empty-id rejection below.
  if (kind === "habitLogs" && !id) {
    const habitId = cut(item.habitId, 100);
    const day = cut(item.day, 10);
    if (!habitId || !DAY_PATTERN.test(day)) return null;
    return habitLogRecord(habitId, day, item, cleanTime(item.updatedAt));
  }
  if (!id) return null;
  // B11 (SA-P2-12) — v1 SUNSET: DEFERRED by decision, flag only.
  // v1 plaintext records are still accepted below. The sunset must NOT be
  // implemented until 0.35.0 is published and adopted: the published 0.34.0
  // clients depend on v1, and rejecting them would strand their sync.
  // See update-site/docs/H1_V1_SUNSET.md. When the sunset ships, this
  // branch is where the rejection goes (and v1 rows stop being stored).
  // H1 - v2 encrypted envelope: the content is opaque ciphertext. The server keeps
  // only id/updatedAt/deleted for the last-write-wins merge and never inspects `enc`,
  // so it cannot read tasks even with the Blob token or the Vercel dashboard.
  if (item.v === 2) {
    const enc = typeof item.enc === "string" ? item.enc : "";
    if (!/^[A-Za-z0-9+/=]+$/.test(enc) || enc.length < 32 || enc.length > MAX_ENC_CHARS) return null;
    const record = { id, updatedAt: cleanTime(item.updatedAt), v: 2, enc };
    if (item.deleted === true) record.deleted = true;
    return record;
  }
  const record = { id, updatedAt: cleanTime(item.updatedAt) };
  // Stored subjects/types use `name` after their first normalization; accept both so re-reading
  // and merging an existing snapshot cannot silently drop the label. Only the three original
  // collections carry a title/name; the «حياتي» kinds name their label field explicitly below.
  if (kind === "tasks" || kind === "subjects" || kind === "taskTypes") {
    const title = cut(item.title ?? item.name, MAX_TITLE);
    if (title) record[kind === "subjects" || kind === "taskTypes" ? "name" : "title"] = title;
  }

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
  if (kind === "habits") {
    const name = cut(item.name ?? item.title, MAX_TITLE);
    if (name) record.name = name;
    const createdAt = Number(item.createdAt);
    if (Number.isSafeInteger(createdAt) && createdAt >= 0) record.createdAt = createdAt;
    if (item.archived === true) record.archived = true;
  }
  if (kind === "habitLogs") return habitLogRecord(cut(item.habitId, 100), cut(item.day, 10), item, record.updatedAt, id);
  if (kind === "wirdLogs") {
    const day = cut(item.day, 10);
    if (!DAY_PATTERN.test(day)) return null;
    record.day = day;
    const pages = Number(item.pages);
    if (Number.isInteger(pages) && pages >= 1 && pages <= 604) record.pages = pages;
    const surah = cut(item.surah, 100);
    if (surah) record.surah = surah;
  }
  if (kind === "healthLogs") {
    const day = cut(item.day, 10);
    if (!DAY_PATTERN.test(day)) return null;
    record.day = day;
    const weight = Number(item.weight);
    if (Number.isFinite(weight) && weight >= 20 && weight <= 500) record.weight = weight;
    const sleepHours = Number(item.sleepHours);
    if (Number.isFinite(sleepHours) && sleepHours >= 0 && sleepHours <= 24) record.sleepHours = sleepHours;
    const waterCups = Number(item.waterCups);
    if (Number.isInteger(waterCups) && waterCups >= 0 && waterCups <= 100) record.waterCups = waterCups;
    const exerciseMinutes = Number(item.exerciseMinutes);
    if (Number.isInteger(exerciseMinutes) && exerciseMinutes >= 0 && exerciseMinutes <= 1440) record.exerciseMinutes = exerciseMinutes;
  }
  if (kind === "transactions") {
    const txKind = cut(item.kind, 24).toLowerCase();
    if (MONEY_KINDS.has(txKind)) record.kind = txKind;
    const amount = Number(item.amount);
    if (Number.isFinite(amount) && amount > 0 && amount <= 1e12) record.amount = amount;
    const category = cut(item.category, 100);
    if (category) record.category = category;
    const day = cut(item.day, 10);
    if (!DAY_PATTERN.test(day)) return null;
    record.day = day;
    const createdAt = Number(item.createdAt);
    if (Number.isSafeInteger(createdAt) && createdAt >= 0) record.createdAt = createdAt;
    // `note` is deliberately never copied: a money note stays on its device, like task notes.
  }
  if (kind === "moodLogs") {
    const day = cut(item.day, 10);
    if (!DAY_PATTERN.test(day)) return null;
    record.day = day;
    const mood = Number(item.mood);
    if (Number.isInteger(mood) && mood >= 1 && mood <= 5) record.mood = mood;
  }
  if (kind === "calorieLogs") {
    const day = cut(item.day, 10);
    if (!DAY_PATTERN.test(day)) return null;
    record.day = day;
    const name = cut(item.name ?? item.title, 48);
    if (name) record.name = name;
    const calories = Number(item.calories);
    if (Number.isInteger(calories) && calories >= 1 && calories <= 10000) record.calories = calories;
    const createdAt = Number(item.createdAt);
    if (Number.isSafeInteger(createdAt) && createdAt >= 0) record.createdAt = createdAt;
  }
  if (item.deleted === true) record.deleted = true;
  return record;
}

/**
 * One habit-log row: its id is "habitId:day" and its only state is presence. Un-marking a day
 * is a delete, so `done: false` normalizes to a tombstone here and is never stored as a field.
 */
function habitLogRecord(habitId, day, item, updatedAt, id) {
  if (!habitId || !DAY_PATTERN.test(day)) return null;
  const record = { id: id || `${habitId}:${day}`, updatedAt };
  record.habitId = habitId;
  record.day = day;
  if (item && item.done === false) record.deleted = true;
  else if (item && item.deleted === true) record.deleted = true;
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
 * produce a different result depending on which one speaks last - with one H1 exception:
 * a v2 envelope replaces a v1 plaintext record at an equal timestamp, because the content
 * is identical (only re-encrypted) and the first v2 push after an upgrade must actually
 * migrate the stored copy instead of leaving plaintext on the server forever.
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
      const upgradesToEncrypted =
        record.v === 2 && current && current.v !== 2 && record.updatedAt >= current.updatedAt;
      if (!current || record.updatedAt > current.updatedAt || upgradesToEncrypted) {
        byId.set(record.id, record);
      }
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

/**
 * H1: a v1 client (published 0.34.0) cannot decrypt v2 envelopes, so it must never
 * receive them - otherwise its merge would treat an envelope as a plaintext record
 * and wipe the local fields. includeEncrypted=false strips them; the revision is
 * unchanged, so the client still sees the snapshot move forward.
 */
async function readSync(profileId, includeEncrypted = true) {
  const record = await readJson(syncPathFor(profileId), { revision: 0, updatedAt: 0, data: {} });
  const data = normalizeSyncPayload(record.data || {});
  if (!includeEncrypted) {
    for (const kind of COLLECTIONS) data[kind] = data[kind].filter((r) => r.v !== 2);
  }
  return {
    revision: cleanTime(record.revision),
    updatedAt: cleanTime(record.updatedAt),
    data,
  };
}

/** Same stripping for a merged snapshot returned by push. */
function stripEncrypted(snapshot) {
  const data = {};
  for (const kind of COLLECTIONS) {
    const list = snapshot && snapshot.data && Array.isArray(snapshot.data[kind])
      ? snapshot.data[kind]
      : [];
    data[kind] = list.filter((r) => r.v !== 2);
  }
  return { revision: snapshot.revision, updatedAt: snapshot.updatedAt, data };
}

async function writeSync(profileId, record) {
  await writeJson(syncPathFor(profileId), record);
}

/** P-02: permanently removes a stored blob (or its local file fallback). */
async function deleteJson(blobPath) {
  const token = storageToken(ACCESS);
  if (token) {
    const { del } = await import("@vercel/blob");
    await del(blobPath, { access: ACCESS, token });
    return;
  }
  try {
    fs.unlinkSync(path.join(LOCAL_DIR, path.basename(blobPath)));
  } catch {
    // Already gone locally; deletion is still complete.
  }
}

/** P-02: deletes the stored sync snapshot for one account. */
async function deleteSync(profileId) {
  await deleteJson(syncPathFor(profileId));
}

/** P-02: deletes the sync account (passphrase hash) for one profile id. */
async function deleteAccount(profileId) {
  const accounts = await readAccounts();
  const next = accounts.filter((item) => item.profileId !== profileId);
  if (next.length === accounts.length) return false;
  await writeJson(ACCOUNTS_PATH, { accounts: next });
  return true;
}

module.exports = {
  COLLECTIONS, PASSWORD_MIN, PASSWORD_MAX, MAX_RECORDS, MAX_ENC_CHARS, stripEncrypted,
  normalizeProfileId, validatePassphrase, hashPassphrase, verifyPassphrase,
  normalizeSyncPayload, mergeSync, readAccounts, hasAccount, setAccount,
  authenticate, readSync, writeSync, deleteSync, deleteAccount,
};
