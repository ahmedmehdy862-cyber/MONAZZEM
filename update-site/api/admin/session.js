const { isAdmin, requireAdmin, originIsSame, clearSessionCookie, setSessionCookie, createSession, verifyMfaToken, parseBody, json } = require("../../lib/admin-auth");
const { mustChangePassword, readCredential, writeCredential, sessionVersionOf } = require("../../lib/admin-credentials");
const { appendAdminAudit } = require("../../lib/admin-audit");
const { generateSecret, otpauthUrl, generateBackupCodes, hashBackupCode, verifyTotp } = require("../../lib/totp");

/**
 * Session status, logout, "log out everywhere" and MFA share one function so
 * the Hobby plan stays within its 12-function limit. `vercel.json` rewrites
 * /api/admin/logout here; the two paths are also distinct methods.
 *
 * POST actions (body.action):
 *   (none)            logout — clears the cookie only
 *   logout-all        bumps the session generation: every session dies,
 *                     including this one (the cookie is cleared too)
 *   mfa-enroll        (session) generates a TOTP secret + backup codes,
 *                     stored pending until mfa-verify-enroll confirms it
 *   mfa-verify-enroll (session) confirms the pending secret with a TOTP code
 *   mfa-verify        (mfaToken from login) completes the second step and
 *                     issues the session cookie; a backup code also works
 *                     here, once, and is then consumed
 *   mfa-disable       (session + TOTP code) removes MFA entirely
 */
module.exports = async function session(req, res) {
  if (req.method === "POST") {
    if (!originIsSame(req)) return json(res, 403, { error: "origin_rejected" });
    const body = parseBody(req.body);
    const action = String(body.action || "");
    if (action === "mfa-verify") return mfaVerify(body, res);
    if (!action) {
      clearSessionCookie(res);
      return json(res, 200, { ok: true });
    }
    if (action === "logout-all") return logoutAll(req, res);
    if (action === "mfa-enroll") return mfaEnroll(req, res);
    if (action === "mfa-verify-enroll") return mfaVerifyEnroll(req, body, res);
    if (action === "mfa-disable") return mfaDisable(req, body, res);
    return json(res, 400, { error: "invalid_action" });
  }
  if (req.method !== "GET") return json(res, 405, { error: "method_not_allowed" });
  if (!process.env.ADMIN_USERNAME || !process.env.ADMIN_SESSION_SECRET) return json(res, 503, { error: "admin_auth_unconfigured" });
  const authenticated = isAdmin(req);
  let mustChange = false;
  let mfaEnrolled = false;
  if (authenticated) {
    try { mustChange = await mustChangePassword(); } catch { mustChange = false; }
    try {
      const stored = await readCredential();
      mfaEnrolled = Boolean(stored?.mfa?.enabled);
    } catch { mfaEnrolled = false; }
  }
  return json(res, 200, {
    authenticated,
    username: authenticated ? process.env.ADMIN_USERNAME : null,
    mustChangePassword: mustChange,
    mfaEnrolled,
  });
};

async function logoutAll(req, res) {
  const gate = await requireAdmin(req, { allowMustChange: true });
  if (!gate.ok) return json(res, gate.status, { error: gate.error });
  try {
    const stored = await readCredential();
    if (!stored) return json(res, 400, { error: "no_stored_credential" });
    await writeCredential({ ...stored, sessionVersion: sessionVersionOf(stored) + 1, updatedAt: new Date().toISOString() });
  } catch {
    return json(res, 503, { error: "credential_unavailable" });
  }
  clearSessionCookie(res);
  await appendAdminAudit([{ actor: process.env.ADMIN_USERNAME || "admin", action: "logout_all", outcome: "ok", detail: "all admin sessions revoked" }]);
  return json(res, 200, { ok: true });
}

async function mfaEnroll(req, res) {
  // Enrolment is a security setup step, not a bootstrap step: the admin must
  // have cleared mustChange first (B2 rule: mutations reject mustChange).
  const gate = await requireAdmin(req);
  if (!gate.ok) return json(res, gate.status, { error: gate.error });
  let stored;
  try {
    stored = await readCredential();
  } catch {
    return json(res, 503, { error: "credential_unavailable" });
  }
  if (!stored) return json(res, 400, { error: "no_stored_credential" });
  if (stored.mfa?.enabled) return json(res, 409, { error: "mfa_already_enabled" });
  const secret = generateSecret();
  const backupCodes = generateBackupCodes();
  await writeCredential({
    ...stored,
    mfa: {
      secret,
      enabled: false,
      backupCodes: backupCodes.map(hashBackupCode),
      enrolledAt: "",
    },
    updatedAt: new Date().toISOString(),
  });
  await appendAdminAudit([{ actor: process.env.ADMIN_USERNAME || "admin", action: "mfa_enroll", outcome: "ok", detail: "TOTP secret generated, pending confirmation" }]);
  return json(res, 200, {
    ok: true,
    secret,
    otpauthUrl: otpauthUrl(secret, process.env.ADMIN_USERNAME),
    // Shown ONCE: the codes are stored only as hashes afterwards.
    backupCodes,
  });
}

async function mfaVerifyEnroll(req, body, res) {
  const gate = await requireAdmin(req);
  if (!gate.ok) return json(res, gate.status, { error: gate.error });
  let stored;
  try {
    stored = await readCredential();
  } catch {
    return json(res, 503, { error: "credential_unavailable" });
  }
  if (!stored?.mfa?.secret || stored.mfa.enabled) return json(res, 400, { error: "mfa_not_pending" });
  if (!verifyTotp(stored.mfa.secret, body.code)) {
    await appendAdminAudit([{ actor: process.env.ADMIN_USERNAME || "admin", action: "mfa_enroll", outcome: "failed", detail: "confirmation code did not match" }]);
    return json(res, 401, { error: "invalid_totp" });
  }
  await writeCredential({
    ...stored,
    mfa: { ...stored.mfa, enabled: true, enrolledAt: new Date().toISOString() },
    updatedAt: new Date().toISOString(),
  });
  await appendAdminAudit([{ actor: process.env.ADMIN_USERNAME || "admin", action: "mfa_enroll", outcome: "ok", detail: "TOTP confirmed, MFA enabled" }]);
  return json(res, 200, { ok: true, mfaEnabled: true });
}

async function mfaVerify(body, res) {
  const username = verifyMfaToken(body.mfaToken);
  if (!username) return json(res, 401, { error: "invalid_mfa_token" });
  let stored;
  try {
    stored = await readCredential();
  } catch {
    return json(res, 503, { error: "credential_unavailable" });
  }
  if (!stored?.mfa?.enabled || !stored.mfa.secret) return json(res, 400, { error: "mfa_not_enabled" });
  const code = String(body.code || "").trim();
  if (verifyTotp(stored.mfa.secret, code)) {
    setSessionCookie(res, createSession(username, { version: sessionVersionOf(stored), mustChange: Boolean(stored.mustChange) }));
    await appendAdminAudit([{ actor: username, action: "login", outcome: "ok", detail: "second factor (TOTP) verified" }]);
    return json(res, 200, { ok: true, username, mustChangePassword: Boolean(stored.mustChange) });
  }
  // One-time backup code: a match consumes it (removed from the stored hashes).
  const candidate = hashBackupCode(code);
  const remaining = (stored.mfa.backupCodes || []).filter((hash) => hash !== candidate);
  if (remaining.length !== (stored.mfa.backupCodes || []).length) {
    await writeCredential({
      ...stored,
      mfa: { ...stored.mfa, backupCodes: remaining },
      updatedAt: new Date().toISOString(),
    });
    setSessionCookie(res, createSession(username, { version: sessionVersionOf(stored), mustChange: Boolean(stored.mustChange) }));
    await appendAdminAudit([{ actor: username, action: "login", outcome: "ok", detail: "second factor (backup code) verified, code consumed" }]);
    return json(res, 200, { ok: true, username, mustChangePassword: Boolean(stored.mustChange), backupCodesLeft: remaining.length });
  }
  await appendAdminAudit([{ actor: username, action: "login", outcome: "failed", detail: "second factor did not match" }]);
  return json(res, 401, { error: "invalid_totp" });
}

async function mfaDisable(req, body, res) {
  const gate = await requireAdmin(req);
  if (!gate.ok) return json(res, gate.status, { error: gate.error });
  let stored;
  try {
    stored = await readCredential();
  } catch {
    return json(res, 503, { error: "credential_unavailable" });
  }
  if (!stored?.mfa?.enabled) return json(res, 400, { error: "mfa_not_enabled" });
  if (!verifyTotp(stored.mfa.secret, body.code)) return json(res, 401, { error: "invalid_totp" });
  const { mfa, ...rest } = stored;
  await writeCredential({ ...rest, updatedAt: new Date().toISOString() });
  await appendAdminAudit([{ actor: process.env.ADMIN_USERNAME || "admin", action: "mfa_disable", outcome: "ok", detail: "MFA removed" }]);
  return json(res, 200, { ok: true });
}
