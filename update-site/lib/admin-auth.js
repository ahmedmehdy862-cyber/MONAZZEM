const crypto = require("node:crypto");

const COOKIE = "__Host-focusstudy_admin";
const SESSION_SECONDS = 12 * 60 * 60;
const MFA_TOKEN_SECONDS = 5 * 60;

function safeEqual(left, right) {
  const a = Buffer.from(String(left || ""));
  const b = Buffer.from(String(right || ""));
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

function parseBody(body) {
  if (body && typeof body === "object") return body;
  try { return JSON.parse(body || "{}"); } catch { return {}; }
}

function originIsSame(req) {
  const origin = req.headers.origin;
  const host = String(req.headers["x-forwarded-host"] || req.headers.host || "").split(",")[0].trim();
  if (!origin || !host) return false;
  try {
    const parsed = new URL(origin);
    return parsed.protocol === "https:" && parsed.host.toLowerCase() === host.toLowerCase();
  } catch {
    return false;
  }
}

function cookieValue(req) {
  const cookies = String(req.headers.cookie || "").split(";");
  const prefix = `${COOKIE}=`;
  const item = cookies.map((value) => value.trim()).find((value) => value.startsWith(prefix));
  return item ? decodeURIComponent(item.slice(prefix.length)) : "";
}

function signature(payload) {
  const key = process.env.ADMIN_SESSION_SECRET;
  if (!key) throw new Error("admin_auth_unconfigured");
  return crypto.createHmac("sha256", key).update(payload).digest("base64url");
}

/**
 * B2 (SA-P1-1): sessions now carry a `jti` (unique id) and a `v` (session
 * generation). The generation lives in the stored credential record and is
 * bumped on every password change and on "log out everywhere", which revokes
 * every outstanding session. `mc` records whether the session was issued
 * while a password change was still pending.
 */
function createSession(username, { version = 0, mustChange = false } = {}) {
  const payload = Buffer.from(JSON.stringify({
    sub: username,
    exp: Math.floor(Date.now() / 1000) + SESSION_SECONDS,
    v: version,
    jti: crypto.randomUUID(),
    mc: Boolean(mustChange),
  })).toString("base64url");
  return `${payload}.${signature(payload)}`;
}

/** Parses a signed session payload, or null when the cookie is absent/forged. */
function sessionPayload(req) {
  try {
    const [payload, supplied] = cookieValue(req).split(".");
    if (!payload || !supplied || !safeEqual(supplied, signature(payload))) return null;
    const session = JSON.parse(Buffer.from(payload, "base64url").toString("utf8"));
    if (session.sub !== process.env.ADMIN_USERNAME) return null;
    if (!(session.exp > Math.floor(Date.now() / 1000))) return null;
    return session;
  } catch {
    return null;
  }
}

function isAdmin(req) {
  return sessionPayload(req) !== null;
}

/**
 * B3: short-lived second-step token issued after the password check when MFA
 * is enabled. It proves "password OK, second factor still pending" for 5
 * minutes and can never become a session on its own.
 */
function createMfaToken(username) {
  const payload = Buffer.from(JSON.stringify({
    sub: username, purpose: "mfa",
    exp: Math.floor(Date.now() / 1000) + MFA_TOKEN_SECONDS,
    jti: crypto.randomUUID(),
  })).toString("base64url");
  return `${payload}.${signature(payload)}`;
}

function verifyMfaToken(token) {
  try {
    const [payload, supplied] = String(token || "").split(".");
    if (!payload || !supplied || !safeEqual(supplied, signature(payload))) return null;
    const data = JSON.parse(Buffer.from(payload, "base64url").toString("utf8"));
    if (data.purpose !== "mfa") return null;
    if (!(data.exp > Math.floor(Date.now() / 1000))) return null;
    if (data.sub !== process.env.ADMIN_USERNAME) return null;
    return data.sub;
  } catch {
    return null;
  }
}

/**
 * B2: full session check for admin endpoints. Beyond the signature/expiry,
 * it verifies the session generation against the stored credential (a bumped
 * generation revokes the session) and, for mutating endpoints, enforces
 * `mustChangePassword` server-side (SA-P1-3) instead of relying on the panel
 * hiding itself. A credential-read error fails closed (503), never falls
 * back to trusting the session.
 *
 * Returns {ok:true, username, mustChange, version} or
 * {ok:false, status, error}.
 */
async function requireAdmin(req, { allowMustChange = false } = {}) {
  const session = sessionPayload(req);
  if (!session) return { ok: false, status: 401, error: "authentication_required" };
  let stored = null;
  try {
    // Lazy require: admin-credentials never imports this module, so there is
    // no cycle; lazy loading also keeps the unit-test mocks simple.
    const { readCredential, sessionVersionOf } = require("./admin-credentials");
    stored = await readCredential();
    if (stored && sessionVersionOf(stored) !== Number(session.v || 0)) {
      return { ok: false, status: 401, error: "session_revoked" };
    }
    const mustChange = stored ? Boolean(stored.mustChange) : true;
    if (mustChange && !allowMustChange) {
      return { ok: false, status: 403, error: "must_change_password" };
    }
    return { ok: true, username: session.sub, mustChange, version: Number(session.v || 0) };
  } catch (error) {
    if (error?.code === "credential_read_failed") {
      return { ok: false, status: 503, error: "credential_unavailable" };
    }
    throw error;
  }
}

function setSessionCookie(res, token) {
  res.setHeader("Set-Cookie", `${COOKIE}=${encodeURIComponent(token)}; Path=/; Max-Age=${SESSION_SECONDS}; HttpOnly; Secure; SameSite=Strict`);
}

function clearSessionCookie(res) {
  res.setHeader("Set-Cookie", `${COOKIE}=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Strict`);
}

/**
 * B6 (SA-P2-6): security headers on every API JSON response. The admin panel
 * is framed by nothing: `frame-ancestors 'none'` + `X-Frame-Options: DENY`.
 * Static admin HTML gets the same framing/nosniff/referrer headers from
 * vercel.json (its inline scripts forbid a strict CSP there).
 */
function setSecurityHeaders(res) {
  res.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; sandbox");
  res.setHeader("X-Frame-Options", "DENY");
  res.setHeader("X-Content-Type-Options", "nosniff");
  res.setHeader("Referrer-Policy", "no-referrer");
}

function json(res, status, data) {
  setSecurityHeaders(res);
  res.setHeader("Cache-Control", "no-store");
  res.setHeader("Content-Type", "application/json; charset=utf-8");
  return res.status(status).json(data);
}

module.exports = {
  safeEqual, parseBody, originIsSame, createSession, sessionPayload, isAdmin,
  createMfaToken, verifyMfaToken, requireAdmin,
  setSessionCookie, clearSessionCookie, setSecurityHeaders, json,
  COOKIE, SESSION_SECONDS, MFA_TOKEN_SECONDS,
};
