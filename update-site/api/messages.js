const {
  readMessages, upsertUser, readNotificationSounds, normalizeProfileId, appendDeliveryEvents
} = require("../lib/messaging-store");
const { userCode } = require("../lib/user-code");
const { parseBody } = require("../lib/admin-auth");

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
    if (action === "register") return register(body, response);
    if (action === "delivered") return delivered(body, response);
    return response.status(400).json({ error: "invalid_action" });
  }
  if (request.method !== "GET") {
    response.setHeader("Allow", "GET, POST");
    return response.status(405).json({ error: "method_not_allowed" });
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

async function register(body, response) {
  const id = cut(body.uid, 64).toLowerCase();
  if (!/^[a-z0-9-]{6,64}$/.test(id)) {
    return response.status(400).json({ error: "invalid_user" });
  }
  const suggestedProfileId = normalizeProfileId(body.profileId);
  const entry = {
    id,
    name: cut(body.name, 60) || "مستخدم مُنظِّم",
    profile: ALLOWED_PROFILES.has(cut(body.profile, 24)) ? cut(body.profile, 24) : "general",
    versionName: cut(body.versionName, 32),
    versionCode: Math.max(0, Math.min(1_000_000, Number(body.versionCode) || 0)),
  };
  if (suggestedProfileId) entry.profileId = suggestedProfileId;
  try {
    const users = await upsertUser(entry);
    const registered = users.find((item) => item.id === id);
    const profileId = normalizeProfileId(registered && registered.profileId);
    response.setHeader("Cache-Control", "no-store");
    return response.status(200).json({ ok: true, code: userCode(id), profileId });
  } catch {
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
