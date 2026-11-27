const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const { userCode } = require("./user-code");
const { storageToken } = require("./blob-access");

const MESSAGES_PATH = "focus-study/team-messages.json";
const INBOX_PATH = "focus-study/support-inbox.json";
const USERS_PATH = "focus-study/users.json";
const SOUNDS_PATH = "focus-study/notification-sounds.json";
const DELIVERIES_PATH = "focus-study/message-deliveries.json";
const LOCAL_DIR = path.join(__dirname, "..", ".data");
// Broadcast messages are public; the support inbox, user registry and sound choices are
// read/written only through the server-side @vercel/blob client with BLOB_READ_WRITE_TOKEN.
const MESSAGES_ACCESS = "public";
const INBOX_ACCESS = "private";
const USERS_ACCESS = "private";
const SOUNDS_ACCESS = "private";
const DELIVERIES_ACCESS = "private";
const MAX_USERS = 1000;
const MAX_DELIVERIES = 500;
const REGISTRATIONS_PATH = "focus-study/registration-audit.json";
const REGISTRATIONS_ACCESS = "private";
const MAX_REGISTRATIONS = 500;

const SOUND_CATEGORIES = ["prayer", "task", "exam", "team", "update", "daily", "dhikr"];
const SOUND_CHOICES = ["default", "chime", "adhan", "system", "silent"];
const DEFAULT_SOUNDS = Object.fromEntries(SOUND_CATEGORIES.map((key) => [key, "default"]));
const PROFILE_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Keeps only known notification types and known sound ids, so a bad payload cannot change behaviour. */
function normalizeSounds(input) {
  const result = { ...DEFAULT_SOUNDS };
  if (!input || typeof input !== "object" || Array.isArray(input)) return result;
  for (const key of SOUND_CATEGORIES) {
    const value = String(input[key] || "").trim().toLowerCase();
    if (SOUND_CHOICES.includes(value)) result[key] = value;
  }
  return result;
}

async function readBlobJson(blobPath, access) {
  const { get } = await import("@vercel/blob");
  const result = await get(blobPath, { access, token: storageToken(access) });
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

async function readJson(blobPath, fallback, access = MESSAGES_ACCESS, strict = false) {
  if (storageToken(access)) {
    try {
      const record = await readBlobJson(blobPath, access);
      if (record) return record;
    } catch (error) {
      console.error("blob read failed", blobPath, error?.message || "unknown");
      // Private stores must never fall back to an empty local file after a failed read: the next
      // write would overwrite the real registry or the saved sound choices with that empty copy.
      if (strict) throw new Error("private_store_unavailable");
    }
  }
  return readLocalJson(blobPath, fallback);
}

/** Reads the inbox from its new private host, migrating an older public record once. */
async function readInboxJson() {
  if (storageToken(INBOX_ACCESS) || storageToken(MESSAGES_ACCESS)) {
    try {
      const current = await readBlobJson(INBOX_PATH, INBOX_ACCESS);
      if (current) return current;
      const legacy = await readBlobJson(INBOX_PATH, MESSAGES_ACCESS);
      if (legacy) {
        await writeJson(INBOX_PATH, legacy, INBOX_ACCESS);
        try {
          const { del } = await import("@vercel/blob");
          await del(INBOX_PATH, { access: MESSAGES_ACCESS, token: storageToken(MESSAGES_ACCESS) });
        } catch (error) {
          console.error("legacy inbox cleanup failed", error?.message || "unknown");
        }
        console.log("support inbox migrated to private Blob");
        return legacy;
      }
    } catch (error) {
      console.error("private inbox read failed", error?.message || "unknown");
      throw new Error("private_inbox_unavailable");
    }
  }
  return readLocalJson(INBOX_PATH, { items: [] });
}

async function writeJson(blobPath, value, access = MESSAGES_ACCESS) {
  const token = storageToken(access);
  if (token) {
    const { put } = await import("@vercel/blob");
    await put(blobPath, JSON.stringify(value), {
      access,
      token,
      addRandomSuffix: false,
      allowOverwrite: true,
      contentType: "application/json; charset=utf-8",
      cacheControlMaxAge: 30,
    });
    return;
  }
  fs.mkdirSync(LOCAL_DIR, { recursive: true });
  fs.writeFileSync(path.join(LOCAL_DIR, path.basename(blobPath)), JSON.stringify(value), "utf8");
}

async function readMessages() {
  const record = await readJson(MESSAGES_PATH, { messages: [] });
  return Array.isArray(record.messages) ? record.messages : [];
}

async function appendMessage(message) {
  const messages = await readMessages();
  const next = [message, ...messages].slice(0, 40);
  await writeJson(MESSAGES_PATH, { messages: next });
  return next;
}

async function removeMessage(id) {
  const messages = await readMessages();
  const next = messages.filter((item) => item.id !== id);
  await writeJson(MESSAGES_PATH, { messages: next });
  return next;
}

async function readInbox() {
  const record = await readInboxJson();
  return Array.isArray(record.items) ? record.items : [];
}

async function appendSupport(item) {
  const items = await readInbox();
  const next = [item, ...items].slice(0, 500);
  await writeJson(INBOX_PATH, { items: next }, INBOX_ACCESS);
  return next;
}

async function markSupport(id, status) {
  const items = await readInbox();
  const next = items.map((item) => (item.id === id ? { ...item, status } : item));
  await writeJson(INBOX_PATH, { items: next }, INBOX_ACCESS);
  return next;
}

async function readUsers() {
  const record = await readJson(USERS_PATH, { users: [] }, USERS_ACCESS, true);
  return Array.isArray(record.users) ? record.users : [];
}

async function readNotificationSounds() {
  const record = await readJson(SOUNDS_PATH, { sounds: DEFAULT_SOUNDS, updatedAt: "" }, SOUNDS_ACCESS, true);
  return { sounds: normalizeSounds(record.sounds), updatedAt: String(record.updatedAt || "") };
}

async function writeNotificationSounds(input) {
  const sounds = normalizeSounds(input);
  const updatedAt = new Date().toISOString();
  await writeJson(SOUNDS_PATH, { sounds, updatedAt }, SOUNDS_ACCESS);
  return { sounds, updatedAt };
}

async function readDeliveryEvents() {
  const record = await readJson(DELIVERIES_PATH, { events: [] }, DELIVERIES_ACCESS, true);
  return Array.isArray(record.events) ? record.events : [];
}

/** Stable dedupe key: one publish per message, one receipt per message and recipient. */
function deliveryKey(event) {
  const messageId = String(event.messageId || "").trim();
  if (!messageId) return "";
  if (event.event === "delivered") return `delivered:${messageId}:${String(event.recipient || "")}`;
  return `published:${messageId}`;
}

/**
 * Append-only audit trail in the existing private Blob store. No new Vercel function is needed.
 * Retried receipts are ignored, so an offline app reporting the same message twice leaves one row.
 */
/**
 * P-02: permanently deletes one install's user row and every support message
 * it sent (matched by uid). Returns true when a user row existed.
 */
async function deleteUser(id) {
  const users = await readUsers();
  const existing = users.find((item) => item.id === id);
  if (!existing) return false;
  await writeJson(USERS_PATH, {
    users: users.filter((item) => item.id !== id),
  }, USERS_ACCESS);
  const items = await readInbox();
  const remaining = items.filter((item) => String(item.uid || "") !== id);
  if (remaining.length !== items.length) {
    await writeJson(INBOX_PATH, { items: remaining }, INBOX_ACCESS);
  }
  return true;
}

async function readRegistrationEvents() {
  const record = await readJson(REGISTRATIONS_PATH, { events: [] }, REGISTRATIONS_ACCESS, true);
  return Array.isArray(record.events) ? record.events : [];
}

/** H2 audit log: every registration outcome (new / re-registered / rejected). */
async function appendRegistrationEvents(events) {
  const existing = await readRegistrationEvents();
  const added = events.map((event) => ({
    ...event,
    id: `r-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    at: new Date().toISOString(),
  }));
  if (!added.length) return existing;
  const next = [...added, ...existing].slice(0, MAX_REGISTRATIONS);
  await writeJson(REGISTRATIONS_PATH, { events: next }, REGISTRATIONS_ACCESS);
  return next;
}

async function appendDeliveryEvents(events) {
  const existing = await readDeliveryEvents();
  const seen = new Set(existing.map(deliveryKey));
  const added = [];
  for (const event of events) {
    const key = deliveryKey(event);
    if (!key || seen.has(key)) continue;
    seen.add(key);
    added.push({
      ...event,
      key,
      id: `d-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      at: new Date().toISOString(),
    });
  }
  if (!added.length) return existing;
  const next = [...added, ...existing].slice(0, MAX_DELIVERIES);
  await writeJson(DELIVERIES_PATH, { events: next }, DELIVERIES_ACCESS);
  return next;
}

function normalizeProfileId(value) {
  const clean = String(value || "").trim().toLowerCase();
  return PROFILE_ID_PATTERN.test(clean) ? clean : "";
}

/** Server-issued, unique across the registry. The client can only suggest an id we do not know yet. */
function issueProfileId(users) {
  const taken = new Set(users.map((item) => normalizeProfileId(item.profileId)).filter(Boolean));
  let candidate = crypto.randomUUID();
  while (taken.has(candidate)) candidate = crypto.randomUUID();
  return candidate;
}

/**
 * Upsert by anonymous install id, keeping the original first-seen timestamp and profile id.
 *
 * H2: a client-supplied profile id is NEVER adopted - the server always issues its own
 * UUID for a new install (a suggested id is only ever presented as ownership proof, and
 * the handler checks it before calling this). Existing registry rows keep their id.
 *
 * H2: no eviction on unauthenticated registration. When the registry is full, a NEW
 * install is refused (returns null; the handler answers 503 registry_full) instead of
 * silently evicting the oldest user; eviction happens only by manual admin action.
 * Re-registering an existing install never grows the list, so it always succeeds.
 */
async function upsertUser(entry) {
  const users = await readUsers();
  const now = new Date().toISOString();
  const existing = users.find((item) => item.id === entry.id);
  if (!existing && users.length >= MAX_USERS) return null;
  const profileId = normalizeProfileId(existing && existing.profileId) || issueProfileId(users);
  const record = { ...entry, profileId };
  const next = existing
    ? users.map((item) => item.id === entry.id
      ? { ...item, ...record, code: userCode(entry.id), lastSeen: now, updatedAt: now }
      : item)
    : [{ ...record, code: userCode(entry.id), firstSeen: now, lastSeen: now, updatedAt: now }, ...users];
  await writeJson(USERS_PATH, { users: next }, USERS_ACCESS);
  return next;
}

module.exports = {
  readMessages, appendMessage, removeMessage, readInbox, appendSupport, markSupport,
  readUsers, upsertUser, readNotificationSounds, writeNotificationSounds,
  readDeliveryEvents, appendDeliveryEvents,
  readRegistrationEvents, appendRegistrationEvents, deleteUser,
  normalizeSounds, normalizeProfileId, SOUND_CATEGORIES, SOUND_CHOICES, DEFAULT_SOUNDS,
};
