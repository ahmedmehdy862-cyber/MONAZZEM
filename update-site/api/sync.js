const {
  normalizeProfileId, validatePassphrase, hasAccount, setAccount, authenticate,
  normalizeRecoveryCode, setRecoveryCode, verifyRecoveryCode,
  removeAccount, removeSync, readSync, writeSync, mergeSync, maybePruneOrphanedSnapshots,
} = require("../lib/sync-store");
const { readUsers } = require("../lib/messaging-store");
const { parseBody, json } = require("../lib/admin-auth");
const {
  limiter, createLimiter, RESET_DAILY_WINDOW_MS, RESET_DAILY_LIMIT,
} = require("../lib/sync-rate-limit");
const { record: audit } = require("../lib/sync-audit");

const ACTIONS = new Set(["register", "changePassphrase", "setRecoveryCode", "reset", "pull", "push"]);

/**
 * Reset attempts are also capped per account per day (design §3.3: 5/account/day),
 * keyed by profile id — on top of the shared 15-minute per-IP bucket.
 */
const resetDailyLimiter = createLimiter(
  RESET_DAILY_WINDOW_MS,
  { reset: RESET_DAILY_LIMIT },
  (request, action, profileId) => `reset-daily:${profileId}`,
);

/**
 * Private, per-account sync over the existing Vercel Blob project. No Supabase, no new push system.
 *
 * `profileId` is the server-issued UUID from the existing registration handshake; a display name,
 * install id, device id or push token is never accepted as a key. The passphrase is checked on every
 * request and never logged. All actions share one function to stay inside the Hobby limit.
 * Failed authentication is rate-limited per caller; storage errors never spend a caller's slots.
 *
 * Security actions (design sync-password-design-2026-11-28, §2–§3):
 * - `changePassphrase`: explicit password change (current + new); `register` stays the
 *   0.34.0-compatible path and reports `created` so clients show the one-time recovery
 *   code only on first activation.
 * - `setRecoveryCode` / `reset`: single-use recovery codes (scrypt hash server-side).
 *   A reset deletes the account and its snapshot; the device then re-registers with a
 *   new passphrase and uploads its local data. Without a device holding the data, a
 *   forgotten passphrase means the server copy is lost — the API says so by offering
 *   no other recovery path.
 * - `push` accepts an optional `baseRevision` (Option A): a stale base is refused
 *   with 409 `stale_revision` instead of silently overwriting a concurrent writer.
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
  let dailyGate = null;
  if (action === "reset") {
    dailyGate = resetDailyLimiter.check(request, action, profileId);
    if (!dailyGate.allowed) {
      response.setHeader("Retry-After", String(dailyGate.retryAfter));
      return json(response, 429, { error: "too_many_requests" });
    }
  }

  try {
    const outcome = action === "register"
      ? await register(profileId, body)
      : action === "changePassphrase"
        ? await changePassphrase(profileId, body)
        : action === "setRecoveryCode"
          ? await setRecoveryCodeAction(profileId, body)
          : action === "reset"
            ? await reset(profileId, body)
            : action === "pull"
              ? await pull(profileId, body)
              : await push(profileId, body);
    limiter.record(gate.key, action, outcome.status < 400);
    if (dailyGate) resetDailyLimiter.record(dailyGate.key, action, outcome.status < 400);
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
  const existed = await hasAccount(profileId);
  if (existed) {
    const current = validatePassphrase(body.currentPassphrase);
    if (!current || !(await authenticate(profileId, current))) {
      return { status: 401, body: { error: "invalid_credentials" } };
    }
  }
  await setAccount(profileId, passphrase);
  // `created` tells the client whether this was the first activation, so it shows the
  // one-time recovery code only then — a second device linking must not silently
  // invalidate the code the first device already showed. The orphan sweep runs here
  // because register is the only write that always happens while a user is present.
  void maybePruneOrphanedSnapshots();
  return { status: 200, body: { ok: true, created: !existed } };
}

/**
 * Explicit passphrase change (design §2.2, Option A). The client changes the auth hash
 * here and only then updates its stored secret and re-seals (re-pushes) with
 * `baseRevision`; a change that fails here must never touch the stored secret.
 */
async function changePassphrase(profileId, body) {
  const current = validatePassphrase(body.currentPassphrase);
  const next = validatePassphrase(body.passphrase);
  if (!next) return { status: 400, body: { error: "invalid_passphrase" } };
  if (!current || !(await authenticate(profileId, current))) {
    audit("change_passphrase", false);
    return { status: 401, body: { error: "invalid_credentials" } };
  }
  if (next === current) return { status: 400, body: { error: "same_passphrase" } };
  await setAccount(profileId, next);
  audit("change_passphrase", true);
  return { status: 200, body: { ok: true } };
}

/** Stores the scrypt hash of a recovery code; the code itself is never stored. */
async function setRecoveryCodeAction(profileId, body) {
  const passphrase = validatePassphrase(body.passphrase);
  if (!passphrase || !(await authenticate(profileId, passphrase))) {
    return { status: 401, body: { error: "invalid_credentials" } };
  }
  const code = normalizeRecoveryCode(body.recoveryCode);
  if (!code) return { status: 400, body: { error: "invalid_recovery_code" } };
  if (!(await setRecoveryCode(profileId, code))) {
    return { status: 401, body: { error: "invalid_credentials" } };
  }
  audit("recovery_code_set", true);
  return { status: 200, body: { ok: true } };
}

/**
 * Forgot-password reset (design §3.2): a valid single-use recovery code deletes the
 * account and its snapshot, so the device can re-register with a new passphrase and
 * upload its local data. One answer covers every failure — unknown account, malformed
 * code and wrong code are indistinguishable, so a wrong code never reveals whether
 * the account exists. The code dies with the account, so it cannot be reused.
 */
async function reset(profileId, body) {
  const code = normalizeRecoveryCode(body.recoveryCode);
  if (!code || !(await verifyRecoveryCode(profileId, code))) {
    audit("reset_failed", false);
    return { status: 401, body: { error: "invalid_recovery_code" } };
  }
  await removeAccount(profileId);
  await removeSync(profileId);
  audit("reset_succeeded", true);
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
  if (body.baseRevision !== undefined && body.baseRevision !== null) {
    const base = Number(body.baseRevision);
    if (!Number.isSafeInteger(base) || base < 0) {
      return { status: 400, body: { error: "invalid_base_revision" } };
    }
    // Option A (design §2.2): a re-seal push that started from an older revision loses
    // to a concurrent writer instead of silently overwriting it.
    if (base !== current.revision) {
      return { status: 409, body: { error: "stale_revision", revision: current.revision } };
    }
  }
  const merged = mergeSync(current, body.data, Date.now());
  await writeSync(profileId, merged);
  return { status: 200, body: merged };
}
