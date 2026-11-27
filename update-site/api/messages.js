const {
  readMessages, readUsers, upsertUser, readNotificationSounds, normalizeProfileId,
  appendDeliveryEvents, appendRegistrationEvents, deleteUser,
} = require("../lib/messaging-store");
const { authenticate } = require("../lib/sync-store");
const { createMessagesLimiter } = require("../lib/messages-rate-limit");
const { userCode } = require("../lib/user-code");
const { parseBody } = require("../lib/admin-auth");

// H2: one limiter instance per serverless container (same caveat as the sync limiter:
// a cold start resets it; the ownership proof is the real control).
const registerLimiter = createMessagesLimiter();

const cut = (value, max) => String(value || "").trim().slice(0, max);
const ALLOWED_PROFILES = new Set(["", "student", "graduate", "professional", "retired", "general"]);

/**
 * Public feed. Three explicit delivery modes are supported:
 *
 * - `broadcast`: every app that can read the feed.
 * - `audience`: only a named usage profile and/or builds at or above a version.
 * - `person`: only one profile id (the legacy install id is also accepted for 0.29.0 builds).
 *
 * POST /api/messages also carries the quiet registration handshake and the app's delivery receipts.
 * All three share this function so the deployment stays within the Hobby function limit.
 */
module.exports = async function messages(request, response) {
  if (request.method === "POST") {
    const body = parseBody(request.body);
    const action = String(body.action || "");
    if (action === "register") return register(request, body, response);
    if (action === "delete") return remove(request, body, response);
    if (action === "delivered") return delivered(body, response);
    return response.status(400).json({ error: "invalid_action" });
  }
  if (request.method !== "GET") {
    response.setHeader("Allow", "GET, POST");
    return response.status(405).json({ error: "method_not_allowed" });
  }
  // GET /api/health is a rewrite to this function, so the deployment stays inside the Hobby
  // function limit. It repeats the strict private read registration depends on and returns no
  // token, path, user data or other secret.
  if (String(request.query?.health || "") === "1") {
    try {
      await readUsers();
      return response.status(200).json({ blob: "ok" });
    } catch {
      return response.status(200).json({ blob: "unreachable" });
    }
  }
  try {
    const profile = String(request.query?.profile || "").trim();
    const version = Number(request.query?.version || 0);
    const uid = String(request.query?.uid || "").trim().toLowerCase();
    const pid = String(request.query?.pid || "").trim().toLowerCase();
    const [all, sounds] = await Promise.all([readMessages(), readNotificationSounds()]);

    const hasTarget = (item) => Boolean(
      String(item.targetProfileId || "").trim()
      || String(item.targetUserId || "").trim()
      || String(item.targetCode || "").trim()
    );
    // A public short code is never a key: code-only legacy rows are targeted and hidden from all.
    const personMatch = (item) => {
      const wantedProfileId = String(item.targetProfileId || "").trim().toLowerCase();
      const wantedLegacyId = String(item.targetUserId || "").trim().toLowerCase();
      const profileMatch = Boolean(pid) && Boolean(wantedProfileId) && pid === wantedProfileId;
      const legacyMatch = Boolean(uid) && Boolean(wantedLegacyId) && uid === wantedLegacyId;
      return profileMatch || legacyMatch;
    };
    const audienceMatch = (item) => {
      const audience = String(item.audienceProfile || "").trim();
      const minVersion = Number(item.minVersionCode || 0);
      if (audience && profile && audience !== profile) return false;
      if (audience && !profile) return false;
      if (minVersion > 0 && version > 0 && version < minVersion) return false;
      return true;
    };
    const visible = (item) => {
      const mode = String(item.targetMode || "");
      if (mode === "broadcast") return true;
      if (mode === "person") return personMatch(item);
      if (mode === "audience") return audienceMatch(item);
      // Rows published before Phase 2 have no mode; their fields decide the mode, with the same
      // exclusivity rule so an old targeted message never becomes a broadcast.
      return hasTarget(item) ? personMatch(item) : audienceMatch(item);
    };

    const messages = all.filter(visible);
    // Personalised answers depend on uid/pid and must never be stored in a shared cache; anonymous
    // broadcasts stay cacheable, so the feed remains cheap without delaying targeted messages.
    response.setHeader(
      "Cache-Control",
      uid || pid ? "private, no-store" : "public, s-maxage=60, stale-while-revalidate=600"
    );
    return response.status(200).json({ messages, sounds: sounds.sounds });
  } catch {
    return response.status(503).json({ error: "messages_unavailable" });
  }
};

/**
 * H2 (audit §11.2) — registration now requires ownership proof:
 *
 * - A FRESH install id registers freely; the server always issues its own
 *   profile id (a client-suggested id is never adopted).
 * - An ALREADY-REGISTERED install id must prove ownership before the server
 *   returns its profile id: either present the stored profile id (the
 *   legitimate client keeps it in its private prefs) or the sync passphrase
 *   for that profile id. Otherwise the caller learns nothing: 409
 *   `already_registered`, no profile id in the response.
 * - Registration is rate-limited per caller address and per install id, every
 *   outcome is written to the registration audit log, and a full registry
 *   refuses new installs (503 `registry_full`) instead of evicting the
 *   oldest user - eviction is manual admin only.
 */
/**
 * P-02: permanently deletes this install's user row and its support messages.
 * Same ownership proof as registration: the stored profile id or the sync
 * passphrase. The sync snapshot/account are deleted separately through
 * /api/sync?action=delete (different secret), so one leaked secret cannot
 * wipe both.
 */
async function remove(request, body, response) {
  const id = cut(body.uid, 64).toLowerCase();
  if (!/^[a-z0-9-]{6,64}$/.test(id)) {
    return response.status(400).json({ error: "invalid_user" });
  }
  const gate = registerLimiter.check(request, id);
  if (!gate.allowed) {
    response.setHeader("Retry-After", String(gate.retryAfter));
    return response.status(429).json({ error: "too_many_requests" });
  }
  const presentedProfileId = normalizeProfileId(body.profileId);
  try {
    const users = await readUsers();
    const existing = users.find((item) => item.id === id);
    if (!existing) {
      registerLimiter.record(gate.keys);
      response.setHeader("Cache-Control", "no-store");
      return response.status(200).json({ ok: true, deleted: false });
    }
    const storedProfileId = normalizeProfileId(existing.profileId);
    const ownsByProfileId = Boolean(storedProfileId) && presentedProfileId === storedProfileId;
    const ownsByPassphrase = Boolean(storedProfileId) &&
      typeof body.passphrase === "string" && body.passphrase.length > 0 &&
      (await authenticate(storedProfileId, body.passphrase));
    if (!ownsByProfileId && !ownsByPassphrase) {
      await appendRegistrationEvents([{
        event: "delete_rejected", uid: id, profileId: storedProfileId, reason: "no_proof",
      }]);
      registerLimiter.record(gate.keys);
      response.setHeader("Cache-Control", "no-store");
      return response.status(409).json({ error: "already_registered" });
    }
    await deleteUser(id);
    await appendRegistrationEvents([{
      event: "deleted", uid: id, profileId: storedProfileId,
    }]);
    registerLimiter.record(gate.keys);
    response.setHeader("Cache-Control", "no-store");
    return response.status(200).json({ ok: true, deleted: true });
  } catch (error) {
    console.error("deletion failed", error?.message || "unknown");
    return response.status(503).json({ error: "deletion_unavailable" });
  }
}

async function register(request, body, response) {
  const id = cut(body.uid, 64).toLowerCase();
  if (!/^[a-z0-9-]{6,64}$/.test(id)) {
    return response.status(400).json({ error: "invalid_user" });
  }
  const gate = registerLimiter.check(request, id);
  if (!gate.allowed) {
    response.setHeader("Retry-After", String(gate.retryAfter));
    return response.status(429).json({ error: "too_many_requests" });
  }
  const presentedProfileId = normalizeProfileId(body.profileId);
  const entry = {
    id,
    name: cut(body.name, 60) || "مستخدم مُنظِّم",
    profile: ALLOWED_PROFILES.has(cut(body.profile, 24)) ? cut(body.profile, 24) : "general",
    versionName: cut(body.versionName, 32),
    versionCode: Math.max(0, Math.min(1_000_000, Number(body.versionCode) || 0)),
  };
  try {
    const users = await readUsers();
    const existing = users.find((item) => item.id === id);
    if (existing) {
      const storedProfileId = normalizeProfileId(existing.profileId);
      const ownsByProfileId = Boolean(storedProfileId) && presentedProfileId === storedProfileId;
      const ownsByPassphrase = Boolean(storedProfileId) &&
        typeof body.passphrase === "string" && body.passphrase.length > 0 &&
        (await authenticate(storedProfileId, body.passphrase));
      if (!ownsByProfileId && !ownsByPassphrase) {
        await appendRegistrationEvents([{
          event: "register_rejected", uid: id, profileId: storedProfileId, reason: "no_proof",
        }]);
        registerLimiter.record(gate.keys);
        response.setHeader("Cache-Control", "no-store");
        return response.status(409).json({ error: "already_registered" });
      }
    }
    const updated = await upsertUser(entry);
    if (!updated) {
      await appendRegistrationEvents([{
        event: "register_rejected", uid: id, reason: "registry_full",
      }]);
      registerLimiter.record(gate.keys);
      response.setHeader("Cache-Control", "no-store");
      return response.status(503).json({ error: "registry_full" });
    }
    const registered = updated.find((item) => item.id === id);
    const profileId = normalizeProfileId(registered && registered.profileId);
    await appendRegistrationEvents([{
      event: "registered", uid: id, profileId, isNew: !existing,
    }]);
    registerLimiter.record(gate.keys);
    response.setHeader("Cache-Control", "no-store");
    return response.status(200).json({ ok: true, code: userCode(id), profileId });
  } catch (error) {
    console.error("registration failed", error?.message || "unknown");
    return response.status(503).json({ error: "registration_unavailable" });
  }
}

/** App-side receipt, stored in the private audit ledger. Duplicate reports are ignored by the store. */
async function delivered(body, response) {
  const uid = cut(body.uid, 64).toLowerCase();
  const pid = normalizeProfileId(body.pid);
  const validUid = /^[a-z0-9-]{6,64}$/.test(uid);
  if (!validUid && !pid) return response.status(400).json({ error: "invalid_user" });
  const ids = Array.isArray(body.ids)
    ? body.ids.map((id) => cut(id, 80)).filter(Boolean).slice(0, 50)
    : [];
  if (!ids.length) return response.status(400).json({ error: "invalid_ids" });

  try {
    const all = await readMessages();
    const byId = new Map(all.map((item) => [String(item.id), item]));
    const recipient = pid || uid;
    const events = ids.filter((id) => byId.has(id)).map((id) => {
      const item = byId.get(id);
      return {
        event: "delivered",
        messageId: id,
        messageTitle: String(item.title || "").slice(0, 90),
        targetMode: String(item.targetMode || ""),
        recipient,
      };
    });
    await appendDeliveryEvents(events);
    response.setHeader("Cache-Control", "no-store");
    return response.status(200).json({ ok: true });
  } catch {
    return response.status(503).json({ error: "delivery_log_unavailable" });
  }
}
