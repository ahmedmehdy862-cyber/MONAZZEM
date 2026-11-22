const {
  normalizeProfileId, validatePassphrase, hasAccount, setAccount, authenticate,
  readSync, writeSync, mergeSync,
} = require("../lib/sync-store");
const { readUsers } = require("../lib/messaging-store");
const { parseBody, json } = require("../lib/admin-auth");
const { limiter } = require("../lib/sync-rate-limit");

const ACTIONS = new Set(["register", "pull", "push"]);

/**
 * Private, per-account sync over the existing Vercel Blob project. No Supabase, no new push system.
 *
 * `profileId` is the server-issued UUID from the existing registration handshake; a display name,
 * install id, device id or push token is never accepted as a key. The passphrase is checked on every
 * request and never logged. All three actions share one function to stay inside the Hobby limit.
 * Failed authentication is rate-limited per caller; storage errors never spend a caller's slots.
 */
module.exports = async function sync(request, response) {
  if (request.method !== "POST") {
    response.setHeader("Allow", "POST");
    return json(response, 405, { error: "method_not_allowed" });
  }

  const body = parseBody(request.body);
  const action = String(body.action || "");
  const profileId = normalizeProfileId(body.profileId);
  if (!profileId) return json(response, 400, { error: "invalid_profile" });
  if (!ACTIONS.has(action)) return json(response, 400, { error: "invalid_action" });

  const gate = limiter.check(request, action, profileId);
  if (!gate.allowed) {
    response.setHeader("Retry-After", String(gate.retryAfter));
    return json(response, 429, { error: "too_many_requests" });
  }

  try {
    const outcome = action === "register"
      ? await register(profileId, body)
      : action === "pull"
        ? await pull(profileId, body)
        : await push(profileId, body);
    limiter.record(gate.key, action, outcome.status < 400);
    return json(response, outcome.status, outcome.body);
  } catch (error) {
    console.error("sync request failed", action, error?.message || "unknown");
    return json(response, 503, { error: "sync_unavailable" });
  }
};

/** First use creates the sync secret; changing it always requires the previous one. */
async function register(profileId, body) {
  const passphrase = validatePassphrase(body.passphrase);
  if (!passphrase) return { status: 400, body: { error: "invalid_passphrase" } };

  const users = await readUsers();
  if (!users.some((item) => normalizeProfileId(item.profileId) === profileId)) {
    return { status: 403, body: { error: "profile_unknown" } };
  }
  if (await hasAccount(profileId)) {
    const current = validatePassphrase(body.currentPassphrase);
    if (!current || !(await authenticate(profileId, current))) {
      return { status: 401, body: { error: "invalid_credentials" } };
    }
  }
  await setAccount(profileId, passphrase);
  return { status: 200, body: { ok: true } };
}

async function pull(profileId, body) {
  const passphrase = validatePassphrase(body.passphrase);
  if (!passphrase || !(await authenticate(profileId, passphrase))) {
    return { status: 401, body: { error: "invalid_credentials" } };
  }
  return { status: 200, body: await readSync(profileId) };
}

async function push(profileId, body) {
  const passphrase = validatePassphrase(body.passphrase);
  if (!passphrase || !(await authenticate(profileId, passphrase))) {
    return { status: 401, body: { error: "invalid_credentials" } };
  }
  const current = await readSync(profileId);
  const merged = mergeSync(current, body.data, Date.now());
  await writeSync(profileId, merged);
  return { status: 200, body: merged };
}
