const { safeEqual, parseBody, originIsSame, createSession, createMfaToken, setSessionCookie, json } = require("../../lib/admin-auth");
const { readCredential, verifyPassword, sessionVersionOf } = require("../../lib/admin-credentials");
const { appendAdminAudit } = require("../../lib/admin-audit");

const attempts = new Map();

module.exports = async function login(req, res) {
  if (req.method !== "POST") return json(res, 405, { error: "method_not_allowed" });
  if (!originIsSame(req)) return json(res, 403, { error: "origin_rejected" });
  const username = process.env.ADMIN_USERNAME;
  if (!username || !process.env.ADMIN_SESSION_SECRET) return json(res, 503, { error: "admin_auth_unconfigured" });

  const address = String(req.headers["x-forwarded-for"] || "unknown").split(",")[0].trim();
  const now = Date.now();
  const current = attempts.get(address);
  if (current && current.until > now && current.count >= 8) return json(res, 429, { error: "try_later" });
  if (!current || current.until <= now) attempts.set(address, { count: 0, until: now + 15 * 60 * 1000 });

  const body = parseBody(req.body);
  // B2 (SA-P1-2): a credential-read error fails closed (503) and NEVER falls
  // back to the env bootstrap password. Only "no stored credential" may use it.
  let stored;
  try {
    stored = await readCredential();
  } catch {
    return json(res, 503, { error: "credential_unavailable" });
  }
  const bootstrapPassword = process.env.ADMIN_PASSWORD || "";
  const userMatches = safeEqual(body.username, (stored && stored.username) || username);
  const passwordMatches = stored ? verifyPassword(body.password, stored) : safeEqual(body.password, bootstrapPassword);

  if (!userMatches || !passwordMatches) {
    const state = attempts.get(address);
    attempts.set(address, { count: (state?.count || 0) + 1, until: state?.until || now + 15 * 60 * 1000 });
    await appendAdminAudit([{ actor: String(body.username || "").slice(0, 64) || username, action: "login", outcome: "failed", detail: "invalid credentials" }]);
    return json(res, 401, { error: "invalid_credentials" });
  }

  attempts.delete(address);
  const mustChange = stored ? Boolean(stored.mustChange) : true;
  // B3: when MFA is enrolled, the password alone buys nothing but a
  // 5-minute second-step token. No session cookie is set here.
  if (stored?.mfa?.enabled && stored.mfa.secret) {
    await appendAdminAudit([{ actor: username, action: "login", outcome: "mfa_required", detail: "password ok, second factor pending" }]);
    return json(res, 200, { ok: true, username, mustChangePassword: mustChange, mfaRequired: true, mfaToken: createMfaToken(username) });
  }
  setSessionCookie(res, createSession(username, { version: sessionVersionOf(stored), mustChange }));
  await appendAdminAudit([{ actor: username, action: "login", outcome: "ok", detail: mustChange ? "bootstrap password, change required" : "session issued" }]);
  return json(res, 200, { ok: true, username, mustChangePassword: mustChange });
};
