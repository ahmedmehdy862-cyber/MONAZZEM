const { requireAdmin, createSession, setSessionCookie, parseBody, safeEqual, originIsSame, json } = require("../../lib/admin-auth");
const { readCredential, writeCredential, hashPassword, verifyPassword, sessionVersionOf } = require("../../lib/admin-credentials");
const { appendAdminAudit } = require("../../lib/admin-audit");
const { createFixedWindowLimiter } = require("../../lib/rate-limit");

// B7 (SA-P3-13): password changes are rate-limited like registration:
// 10/IP + 5/username per 15 min, in-memory (see lib/rate-limit.js).
const passwordLimiter = createFixedWindowLimiter("admin-password");

/**
 * Requires a valid session. The bootstrap password from env works once, then the stored
 * scrypt hash becomes the only accepted credential, so the temporary password is retired.
 *
 * B2 (SA-P1-1): changing the password bumps the session generation, which
 * revokes every other outstanding session. The session that performed the
 * change is re-issued with the new generation, so the admin stays signed in.
 * B2 (SA-P1-2): a credential-read error fails closed, never falls back to env.
 */
module.exports = async function password(req, res) {
  if (req.method !== "POST") return json(res, 405, { error: "method_not_allowed" });
  if (!originIsSame(req)) return json(res, 403, { error: "origin_rejected" });
  // The changing session must itself be current (not revoked by an earlier
  // password change); a mustChange session is allowed here — that is the point.
  const session = await requireAdmin(req, { allowMustChange: true });
  if (!session.ok) return json(res, session.status, { error: session.error });
  const gate = passwordLimiter.check(req, process.env.ADMIN_USERNAME || "admin");
  if (!gate.allowed) {
    res.setHeader("Retry-After", String(gate.retryAfter));
    return json(res, 429, { error: "too_many_requests" });
  }
  const body = parseBody(req.body);
  const currentPassword = String(body.currentPassword || "");
  const newPassword = String(body.newPassword || "");
  if (newPassword.length < 10) {
    passwordLimiter.record(gate.keys);
    return json(res, 400, { error: "password_too_short" });
  }
  if (safeEqual(currentPassword, newPassword)) {
    passwordLimiter.record(gate.keys);
    return json(res, 400, { error: "password_unchanged" });
  }

  let stored;
  try {
    stored = await readCredential();
  } catch {
    return json(res, 503, { error: "credential_unavailable" });
  }
  const currentOk = stored ? verifyPassword(currentPassword, stored) : safeEqual(currentPassword, process.env.ADMIN_PASSWORD || "");
  if (!currentOk) {
    passwordLimiter.record(gate.keys);
    await appendAdminAudit([{ actor: process.env.ADMIN_USERNAME || "admin", action: "password_change", outcome: "failed", detail: "current password did not match" }]);
    return json(res, 401, { error: "invalid_credentials" });
  }

  try {
    const { salt, hash } = hashPassword(newPassword);
    const nextVersion = sessionVersionOf(stored) + 1;
    await writeCredential({
      ...(stored || {}),
      username: process.env.ADMIN_USERNAME,
      salt,
      hash,
      mustChange: false,
      sessionVersion: nextVersion,
      updatedAt: new Date().toISOString(),
    });
    // Re-issue the changing session at the new generation; all others die.
    setSessionCookie(res, createSession(process.env.ADMIN_USERNAME, { version: nextVersion, mustChange: false }));
    passwordLimiter.record(gate.keys);
    await appendAdminAudit([{ actor: process.env.ADMIN_USERNAME || "admin", action: "password_change", outcome: "ok", detail: "sessions revoked except the changing session" }]);
    return json(res, 200, { ok: true });
  } catch {
    return json(res, 500, { error: "password_save_failed" });
  }
};
