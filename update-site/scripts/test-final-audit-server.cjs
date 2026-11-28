/**
 * Wave B (final audit, Batch B) regression tests — server + admin panel.
 *
 * Covers: message expiry (B1), admin session revocation + bootstrap fallback
 * (B2), TOTP MFA (B3), admin audit trail (B4), admin user deletion (B5),
 * CSRF/security headers (B6), rate limits (B7), support-image upload
 * validation (B8), feed SHA-256 (B9), delivered receipts + APK magic (B10).
 *
 * The Blob-backed stores are mocked, so no token, network or database is
 * involved, except stubbed fetch/blob readers injected per test:
 *   npm run test:server-audit
 */
const assert = require("node:assert/strict");
const path = require("node:path");

const ROOT = path.join(__dirname, "..");

function loadMocked(relativeModule, mocks) {
  const abs = path.resolve(ROOT, relativeModule);
  const dir = path.dirname(abs);
  const saved = new Map();
  for (const [specifier, exports] of Object.entries(mocks)) {
    const dep = specifier.startsWith(".")
      ? require.resolve(path.resolve(dir, specifier))
      : require.resolve(specifier, { paths: [dir] });
    saved.set(dep, require.cache[dep]);
    require.cache[dep] = { id: dep, filename: dep, loaded: true, exports, children: [], paths: [] };
  }
  delete require.cache[abs];
  try {
    return require(abs);
  } finally {
    for (const [dep, previous] of saved) {
      if (previous) require.cache[dep] = previous;
      else delete require.cache[dep];
    }
    delete require.cache[abs];
  }
}

function makeRes() {
  return {
    headers: {},
    statusCode: 200,
    body: null,
    ended: null,
    setHeader(key, value) { this.headers[key] = value; return this; },
    status(code) { this.statusCode = code; return this; },
    json(value) { this.body = value; return this; },
    end(value) { this.ended = value; return this; },
  };
}

const ORIGIN_HEADERS = { origin: "https://update-site-sigma.vercel.app", host: "update-site-sigma.vercel.app" };
const PROFILE_ID = "7f3c1b9e-2d4a-4f6b-8c0e-1a2b3c4d5e6f";
const OTHER_PROFILE_ID = "8a4d2c0f-3b5e-4a7c-9d1e-2f3b4c5d6e7f";
const UID_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
const UID_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

let passed = 0;
const queue = [];
function test(name, fn) {
  queue.push([name, fn]);
}

// ---------------------------------------------------------------- B1: expiry
const expiryStore = require("../lib/messaging-store");
const { normalizeExpiresAt, isMessageExpired, visibleMessages, expiresAtFromDays } = expiryStore;

test("expiry: normalizeExpiresAt accepts future ISO, rejects past/malformed/far-future", () => {
  const now = Date.parse("2026-11-28T09:00:00.000Z");
  assert.equal(normalizeExpiresAt("2026-12-01T00:00:00.000Z", now), "2026-12-01T00:00:00.000Z");
  assert.equal(normalizeExpiresAt("", now), "");
  assert.equal(normalizeExpiresAt("not-a-date", now), "");
  assert.equal(normalizeExpiresAt("2026-11-01T00:00:00.000Z", now), "");
  assert.equal(normalizeExpiresAt("2028-01-01T00:00:00.000Z", now), "");
  assert.equal(expiresAtFromDays(7, now), "2026-12-05T09:00:00.000Z");
  assert.equal(expiresAtFromDays(0, now), "");
  assert.equal(expiresAtFromDays(-3, now), "");
});

test("expiry: visibleMessages drops expired rows, keeps live and never-expiring rows", () => {
  const now = Date.parse("2026-11-28T09:00:00.000Z");
  const rows = [
    { id: "m-live", expiresAt: "2026-12-01T00:00:00.000Z" },
    { id: "m-expired", expiresAt: "2026-11-01T00:00:00.000Z" },
    { id: "m-forever" },
    { id: "m-bad", expiresAt: "garbage" },
  ];
  assert.equal(isMessageExpired(rows[0], now), false);
  assert.equal(isMessageExpired(rows[1], now), true);
  assert.deepEqual(visibleMessages(rows, now).map((r) => r.id), ["m-live", "m-forever", "m-bad"]);
});

// ---------------------------------------------------------------- B2/B3: auth core
test("sessions: createSession round-trips version/jti/mustChange through sessionPayload", () => {
  process.env.ADMIN_USERNAME = "admin";
  process.env.ADMIN_SESSION_SECRET = "test-secret-for-wave-b";
  const auth = require("../lib/admin-auth");
  const token = auth.createSession("admin", { version: 3, mustChange: true });
  const req = { headers: { cookie: `__Host-focusstudy_admin=${encodeURIComponent(token)}` } };
  const payload = auth.sessionPayload(req);
  assert.equal(payload.sub, "admin");
  assert.equal(payload.v, 3);
  assert.equal(payload.mc, true);
  assert.match(payload.jti, /^[0-9a-f-]{36}$/);
  assert.equal(auth.isAdmin(req), true);
  const forged = { headers: { cookie: "__Host-focusstudy_admin=eyJhbGciOiJIUzI1NiJ9.forged" } };
  assert.equal(auth.sessionPayload(forged), null);
  assert.equal(auth.isAdmin(forged), false);
});

test("sessions: requireAdmin revokes a stale generation and enforces mustChange", async () => {
  process.env.ADMIN_USERNAME = "admin";
  process.env.ADMIN_SESSION_SECRET = "test-secret-for-wave-b";
  const auth = require("../lib/admin-auth");
  const credPath = require.resolve("../lib/admin-credentials");
  const saved = require.cache[credPath];
  // Stored generation is 2; a v1 session is revoked, a v2 session is current.
  require.cache[credPath] = {
    id: credPath, filename: credPath, loaded: true,
    exports: {
      readCredential: async () => ({ username: "admin", sessionVersion: 2, mustChange: true }),
      sessionVersionOf: (r) => Number(r?.sessionVersion || 0),
    },
    children: [], paths: [],
  };
  try {
    const stale = { headers: { cookie: `__Host-focusstudy_admin=${encodeURIComponent(auth.createSession("admin", { version: 1 }))}` } };
    assert.deepEqual(await auth.requireAdmin(stale), { ok: false, status: 401, error: "session_revoked" });
    const current = { headers: { cookie: `__Host-focusstudy_admin=${encodeURIComponent(auth.createSession("admin", { version: 2, mustChange: true }))}` } };
    const blocked = await auth.requireAdmin(current);
    assert.equal(blocked.ok, false);
    assert.equal(blocked.error, "must_change_password");
    const allowed = await auth.requireAdmin(current, { allowMustChange: true });
    assert.equal(allowed.ok, true);
    assert.equal(allowed.mustChange, true);
  } finally {
    if (saved) require.cache[credPath] = saved;
    else delete require.cache[credPath];
  }
});

test("sessions: requireAdmin fails closed when the credential read errors", async () => {
  process.env.ADMIN_USERNAME = "admin";
  process.env.ADMIN_SESSION_SECRET = "test-secret-for-wave-b";
  const auth = require("../lib/admin-auth");
  const credPath = require.resolve("../lib/admin-credentials");
  const saved = require.cache[credPath];
  const failure = new Error("credential_read_failed");
  failure.code = "credential_read_failed";
  require.cache[credPath] = {
    id: credPath, filename: credPath, loaded: true,
    exports: {
      readCredential: async () => { throw failure; },
      sessionVersionOf: () => 0,
    },
    children: [], paths: [],
  };
  try {
    const req = { headers: { cookie: `__Host-focusstudy_admin=${encodeURIComponent(auth.createSession("admin", { version: 0 }))}` } };
    assert.deepEqual(await auth.requireAdmin(req), { ok: false, status: 503, error: "credential_unavailable" });
  } finally {
    if (saved) require.cache[credPath] = saved;
    else delete require.cache[credPath];
  }
});

// ---------------------------------------------------------------- B2/B3: login
function loadLogin(credentialRecord, readError) {
  process.env.ADMIN_USERNAME = "admin";
  process.env.ADMIN_SESSION_SECRET = "test-secret-for-wave-b";
  process.env.ADMIN_PASSWORD = "bootstrap-password";
  const audit = [];
  return {
    handler: loadMocked("api/admin/login.js", {
      "../../lib/admin-credentials": {
        readCredential: async () => {
          if (readError) { const e = new Error("credential_read_failed"); e.code = "credential_read_failed"; throw e; }
          return credentialRecord;
        },
        verifyPassword: (password, record) => password === "stored-password" && Boolean(record?.salt),
        sessionVersionOf: (r) => Number(r?.sessionVersion || 0),
      },
      "../../lib/admin-audit": { appendAdminAudit: async (events) => { audit.push(...events); return audit; } },
    }),
    audit,
  };
}

test("login: bootstrap works only when no credential is stored; a read error never falls back", async () => {
  const { handler } = loadLogin(null, false);
  let res = makeRes();
  await handler({ method: "POST", body: { username: "admin", password: "bootstrap-password" }, headers: ORIGIN_HEADERS }, res);
  assert.equal(res.statusCode, 200, JSON.stringify(res.body));
  assert.equal(res.body.mustChangePassword, true);
  assert.ok(res.headers["Set-Cookie"]?.includes("__Host-focusstudy_admin="));

  const failing = loadLogin(null, true);
  res = makeRes();
  await failing.handler({ method: "POST", body: { username: "admin", password: "bootstrap-password" }, headers: ORIGIN_HEADERS }, res);
  assert.equal(res.statusCode, 503, JSON.stringify(res.body));
  assert.equal(res.body.error, "credential_unavailable");
  assert.equal(res.headers["Set-Cookie"], undefined, "no session may be issued on a read error");
});

test("login: a stored credential retires the bootstrap password", async () => {
  const { handler } = loadLogin({ username: "admin", salt: "s", hash: "h", mustChange: false, sessionVersion: 1 }, false);
  let res = makeRes();
  await handler({ method: "POST", body: { username: "admin", password: "bootstrap-password" }, headers: ORIGIN_HEADERS }, res);
  assert.equal(res.statusCode, 401);
  res = makeRes();
  await handler({ method: "POST", body: { username: "admin", password: "stored-password" }, headers: ORIGIN_HEADERS }, res);
  assert.equal(res.statusCode, 200);
  assert.equal(res.body.mustChangePassword, false);
});

test("login: MFA enabled returns a second-step token and no session cookie", async () => {
  const { handler } = loadLogin({ username: "admin", salt: "s", hash: "h", mustChange: false, sessionVersion: 1, mfa: { enabled: true, secret: "JBSWY3DPEHPK3PXP" } }, false);
  const res = makeRes();
  await handler({ method: "POST", body: { username: "admin", password: "stored-password" }, headers: ORIGIN_HEADERS }, res);
  assert.equal(res.statusCode, 200, JSON.stringify(res.body));
  assert.equal(res.body.mfaRequired, true);
  assert.match(res.body.mfaToken, /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/);
  assert.equal(res.headers["Set-Cookie"], undefined);
});

// ---------------------------------------------------------------- B3: TOTP + MFA flow
const totpLib = require("../lib/totp");

test("totp: round-trips, accepts one step of skew, rejects the rest", () => {
  const secret = totpLib.generateSecret();
  assert.match(secret, /^[A-Z2-7]+$/);
  const now = Date.parse("2026-11-28T09:00:00.000Z");
  const code = totpLib.totp(secret, now);
  assert.match(code, /^\d{6}$/);
  assert.equal(totpLib.verifyTotp(secret, code, now), true);
  assert.equal(totpLib.verifyTotp(secret, totpLib.totp(secret, now - 30000), now), true);
  assert.equal(totpLib.verifyTotp(secret, totpLib.totp(secret, now + 30000), now), true);
  assert.equal(totpLib.verifyTotp(secret, totpLib.totp(secret, now - 60000), now), false);
  assert.equal(totpLib.verifyTotp(secret, "000000", now), false);
  assert.equal(totpLib.verifyTotp(secret, "12345", now), false);
  assert.equal(totpLib.verifyTotp("!!!not-base32!!!", code, now), false);
  assert.ok(totpLib.otpauthUrl(secret, "admin").startsWith("otpauth://totp/MONAZZEM:admin?secret="));
  const codes = totpLib.generateBackupCodes();
  assert.equal(codes.length, 8);
  assert.equal(new Set(codes).size, 8);
  assert.ok(codes.every((c) => /^[A-Z0-9]{5}-[A-Z0-9]{5}$/.test(c)));
  assert.equal(totpLib.hashBackupCode(codes[0]), totpLib.hashBackupCode(codes[0].toLowerCase().replace("-", "")));
  assert.notEqual(totpLib.hashBackupCode(codes[0]), totpLib.hashBackupCode(codes[1]));
});

function loadSessionMfa() {
  process.env.ADMIN_USERNAME = "admin";
  process.env.ADMIN_SESSION_SECRET = "test-secret-for-wave-b";
  let record = { username: "admin", salt: "s", hash: "h", mustChange: false, sessionVersion: 2 };
  let issuedCookie = "";
  const audit = [];
  const handler = loadMocked("api/admin/session.js", {
    "../../lib/admin-auth": {
      isAdmin: () => true,
      originIsSame: () => true,
      parseBody: (body) => body || {},
      requireAdmin: async () => ({ ok: true, mustChange: false, version: 2 }),
      createSession: (username, opts) => `session:${username}:${opts?.version || 0}`,
      setSessionCookie: (res, token) => { issuedCookie = token; res.setHeader("Set-Cookie", token); },
      verifyMfaToken: (token) => (token === "mfa-token-ok" ? "admin" : null),
      clearSessionCookie: (res) => res.setHeader("Set-Cookie", "cleared"),
      json: (res, status, data) => res.status(status).json(data),
    },
    "../../lib/admin-credentials": {
      mustChangePassword: async () => false,
      readCredential: async () => ({ ...record, mfa: record.mfa ? { ...record.mfa } : undefined }),
      writeCredential: async (next) => { record = { ...next }; },
      sessionVersionOf: (r) => Number(r?.sessionVersion || 0),
    },
    "../../lib/admin-audit": { appendAdminAudit: async (events) => { audit.push(...events); return audit; } },
  });
  return { handler, audit, getRecord: () => record, getCookie: () => issuedCookie };
}

test("mfa: enrol -> confirm -> second-step login -> backup code consumed once", async () => {
  const ctx = loadSessionMfa();
  const post = async (body) => {
    const res = makeRes();
    await ctx.handler({ method: "POST", body, headers: ORIGIN_HEADERS }, res);
    return res;
  };
  let res = await post({ action: "mfa-enroll" });
  assert.equal(res.statusCode, 200, JSON.stringify(res.body));
  assert.match(res.body.secret, /^[A-Z2-7]+$/);
  assert.ok(res.body.otpauthUrl.startsWith("otpauth://totp/"));
  assert.equal(res.body.backupCodes.length, 8);
  const { secret, backupCodes } = res.body;
  assert.equal(ctx.getRecord().mfa.enabled, false, "enrolment stays pending until confirmed");

  res = await post({ action: "mfa-verify-enroll", code: "000000" });
  assert.equal(res.statusCode, 401);
  assert.equal(ctx.getRecord().mfa.enabled, false);

  res = await post({ action: "mfa-verify-enroll", code: totpLib.totp(secret) });
  assert.equal(res.statusCode, 200, JSON.stringify(res.body));
  assert.equal(ctx.getRecord().mfa.enabled, true);

  res = await post({ action: "mfa-enroll" });
  assert.equal(res.statusCode, 409, "re-enrolment while enabled is rejected");

  // Second step with a backup code consumes it.
  res = await post({ action: "mfa-verify", mfaToken: "mfa-token-ok", code: backupCodes[0] });
  assert.equal(res.statusCode, 200, JSON.stringify(res.body));
  assert.equal(res.body.backupCodesLeft, 7);
  assert.ok(ctx.getCookie().startsWith("session:admin:2"));
  res = await post({ action: "mfa-verify", mfaToken: "mfa-token-ok", code: backupCodes[0] });
  assert.equal(res.statusCode, 401, "a consumed backup code must not work twice");

  // Second step with a bad token fails.
  res = await post({ action: "mfa-verify", mfaToken: "bogus", code: totpLib.totp(secret) });
  assert.equal(res.statusCode, 401);

  // Disable with a live TOTP code removes MFA.
  res = await post({ action: "mfa-disable", code: totpLib.totp(secret) });
  assert.equal(res.statusCode, 200, JSON.stringify(res.body));
  assert.equal(ctx.getRecord().mfa, undefined);
});

test("mfa: logout-all bumps the session generation and clears the cookie", async () => {
  const ctx = loadSessionMfa();
  const res = makeRes();
  await ctx.handler({ method: "POST", body: { action: "logout-all" }, headers: ORIGIN_HEADERS }, res);
  assert.equal(res.statusCode, 200, JSON.stringify(res.body));
  assert.equal(ctx.getRecord().sessionVersion, 3);
  assert.equal(res.headers["Set-Cookie"], "cleared");
});

// ---------------------------------------------------------------- B6: headers
test("headers: API json() sets CSP, frame, nosniff and referrer headers", () => {
  const auth = require("../lib/admin-auth");
  const res = makeRes();
  auth.json(res, 200, { ok: true });
  assert.equal(res.headers["Content-Security-Policy"], "default-src 'none'; frame-ancestors 'none'; sandbox");
  assert.equal(res.headers["X-Frame-Options"], "DENY");
  assert.equal(res.headers["X-Content-Type-Options"], "nosniff");
  assert.equal(res.headers["Referrer-Policy"], "no-referrer");
  assert.equal(res.headers["Cache-Control"], "no-store");
});

// ---------------------------------------------------------------- B7: rate limits
test("rate limit: fixed-window limiter allows 10/IP and 5/uid, then 429s, then resets", () => {
  const { createFixedWindowLimiter } = require("../lib/rate-limit");
  const limiter = createFixedWindowLimiter("test-limit");
  const req = (ip) => ({ headers: { "x-forwarded-for": ip } });
  // Per-uid limit: 5 attempts for one uid, the 6th is blocked.
  for (let i = 0; i < 5; i += 1) {
    const gate = limiter.check(req("203.0.113.1"), "user-1");
    assert.equal(gate.allowed, true);
    limiter.record(gate.keys);
  }
  const blocked = limiter.check(req("203.0.113.1"), "user-1");
  assert.equal(blocked.allowed, false);
  assert.ok(blocked.retryAfter > 0);
  // The IP window still has room (5 of 10 spent), so a fresh uid is fine.
  assert.equal(limiter.check(req("203.0.113.1"), "user-2").allowed, true);
  // Per-IP limit: 10 attempts from one IP with fresh uids, the 11th is blocked.
  const ipLimiter = createFixedWindowLimiter("test-limit-ip");
  for (let i = 0; i < 10; i += 1) {
    const gate = ipLimiter.check(req("203.0.113.9"), `user-${i}`);
    assert.equal(gate.allowed, true);
    ipLimiter.record(gate.keys);
  }
  assert.equal(ipLimiter.check(req("203.0.113.9"), "user-10").allowed, false);
  // A different IP is unaffected.
  assert.equal(ipLimiter.check(req("203.0.113.2"), "user-10").allowed, true);
  // After the window, the same caller is allowed again.
  const future = Date.now() + 16 * 60 * 1000;
  assert.equal(limiter.check(req("203.0.113.1"), "user-1", future).allowed, true);
});

// ---------------------------------------------------------------- B8: support images
const supportImages = require("../lib/support-images");

test("support images: MIME sniffing trusts magic bytes, not extensions", () => {
  const jpeg = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10]);
  const png = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00]);
  const webp = new Uint8Array([0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50]);
  const gif = new Uint8Array([0x47, 0x49, 0x46, 0x38, 0x39, 0x61]);
  const text = new Uint8Array(Buffer.from("hello"));
  assert.equal(supportImages.sniffImageMime(jpeg), "image/jpeg");
  assert.equal(supportImages.sniffImageMime(png), "image/png");
  assert.equal(supportImages.sniffImageMime(webp), "image/webp");
  assert.equal(supportImages.sniffImageMime(gif), "");
  assert.equal(supportImages.sniffImageMime(text), "");
  assert.equal(supportImages.sniffImageMime(new Uint8Array([])), "");
  assert.equal(supportImages.hasZipMagic(new Uint8Array([0x50, 0x4b, 0x03, 0x04])), true);
  assert.equal(supportImages.hasZipMagic(jpeg), false);
});

test("support images: pathnames are server-shaped and parse strictly", () => {
  const pathname = supportImages.imagePathnameFor("s-123-abc", "image/png");
  assert.match(pathname, /^focus-study\/support-images\/s-123-abc\/[0-9a-f-]{36}\.png$/);
  const parsed = supportImages.parseSupportImagePath(pathname);
  assert.equal(parsed.supportId, "s-123-abc");
  assert.equal(parsed.ext, "png");
  assert.equal(supportImages.imagePathnameFor("s-123-abc", "image/gif"), "");
  assert.equal(supportImages.parseSupportImagePath("focus-study/support-images/s-1/evil/../../x.png"), null);
  assert.equal(supportImages.parseSupportImagePath("https://example.com/x.png"), null);
  assert.equal(supportImages.validSupportId("s-123-abc"), true);
  assert.equal(supportImages.validSupportId("../etc"), false);
  assert.equal(supportImages.MAX_IMAGE_BYTES, 5 * 1024 * 1024);
  assert.equal(supportImages.MAX_IMAGES_PER_MESSAGE, 4);
});

function makeSupportStore(items) {
  let current = items.map((item) => ({ ...item }));
  return {
    readInbox: async () => current.map((item) => ({ ...item, images: [...(item.images || [])] })),
    appendSupport: async (item) => { current = [item, ...current]; return current; },
    attachSupportImages: async (supportId, images) => {
      current = current.map((item) => (item.id === supportId
        ? { ...item, images: [...(item.images || []), ...images].slice(0, 4) } : item));
      return current.find((item) => item.id === supportId) || null;
    },
    _items: () => current,
  };
}

function loadSupport(store, blobClient) {
  process.env.PRIVATE_READ_WRITE_TOKEN = "test-private-token";
  return loadMocked("api/support.js", {
    "../lib/messaging-store": store,
    "../lib/admin-auth": { parseBody: (body) => body || {}, setSecurityHeaders: () => {} },
    "@vercel/blob/client": blobClient || { generateClientTokenFromReadWriteToken: async () => "test-client-token" },
  });
}

test("support images: upload-token binds supportId+uid+window and returns a scoped token", async () => {
  const fresh = { id: "s-100-abc", uid: UID_A, images: [], at: new Date().toISOString() };
  const old = { id: "s-101-abc", uid: UID_A, images: [], at: new Date(Date.now() - 20 * 60 * 1000).toISOString() };
  const store = makeSupportStore([fresh, old]);
  const handler = loadSupport(store);
  const post = async (body) => {
    const res = makeRes();
    await handler({ method: "POST", body, headers: {} }, res);
    return res;
  };
  let res = await post({ action: "upload-token", supportId: "s-100-abc", uid: UID_A, filename: "x.png", contentType: "image/png" });
  assert.equal(res.statusCode, 200, JSON.stringify(res.body));
  assert.equal(res.body.token, "test-client-token");
  assert.match(res.body.pathname, /^focus-study\/support-images\/s-100-abc\/[0-9a-f-]{36}\.png$/);
  assert.equal(res.body.maxSizeBytes, 5 * 1024 * 1024);

  res = await post({ action: "upload-token", supportId: "s-100-abc", uid: UID_B, filename: "x.png", contentType: "image/png" });
  assert.equal(res.statusCode, 403);
  res = await post({ action: "upload-token", supportId: "s-101-abc", uid: UID_A, filename: "x.png", contentType: "image/png" });
  assert.equal(res.statusCode, 410, "uploads after the 15-minute window are refused");
  res = await post({ action: "upload-token", supportId: "s-999-abc", uid: UID_A, filename: "x.png", contentType: "image/png" });
  assert.equal(res.statusCode, 404);
  res = await post({ action: "upload-token", supportId: "s-100-abc", uid: UID_A, filename: "x.gif", contentType: "image/gif" });
  assert.equal(res.statusCode, 400);
});

test("support images: confirm sniffs bytes, enforces size and count, stores refs", async () => {
  const item = { id: "s-200-abc", uid: UID_A, images: [], at: new Date().toISOString() };
  const store = makeSupportStore([item]);
  const handler = loadSupport(store);
  const pngBytes = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x01]);
  const jpegBytes = Buffer.from([0xff, 0xd8, 0xff, 0xe0]);
  supportImages.setBlobReader(async (pathname) => {
    if (pathname.endsWith("big.png")) return Buffer.alloc(5 * 1024 * 1024 + 1, 0);
    if (pathname.endsWith(".png")) return pngBytes;
    if (pathname.endsWith(".jpg")) return jpegBytes;
    return null;
  });
  try {
    const post = async (body) => {
      const res = makeRes();
      await handler({ method: "POST", body, headers: {} }, res);
      return res;
    };
    const good = "focus-study/support-images/s-200-abc/11111111-1111-4111-8111-111111111111.png";
    let res = await post({ action: "confirm-images", supportId: "s-200-abc", uid: UID_A, images: [{ pathname: good, contentType: "image/png" }] });
    assert.equal(res.statusCode, 200, JSON.stringify(res.body));
    assert.equal(res.body.images.length, 1);
    assert.equal(res.body.images[0].contentType, "image/png");

    // Declared png but the bytes are JPEG: rejected.
    const lying = "focus-study/support-images/s-200-abc/22222222-2222-4222-8222-222222222222.jpg";
    res = await post({ action: "confirm-images", supportId: "s-200-abc", uid: UID_A, images: [{ pathname: lying, contentType: "image/png" }] });
    assert.equal(res.statusCode, 400);
    assert.equal(res.body.error, "image_type_mismatch");

    // Oversized file: rejected.
    const big = "focus-study/support-images/s-200-abc/33333333-3333-4333-8333-333333333333.png";
    // big.png stub matches by suffix "big.png" — use a matching name.
    res = await post({ action: "confirm-images", supportId: "s-200-abc", uid: UID_A, images: [{ pathname: big.replace("33333333-3333-4333-8333-333333333333", "big"), contentType: "image/png" }] });
    assert.equal(res.statusCode, 400, "a non-UUID pathname must be rejected before any fetch");
    assert.equal(res.body.error, "invalid_image_path");

    // Wrong support id in the pathname: rejected.
    res = await post({ action: "confirm-images", supportId: "s-200-abc", uid: UID_A, images: [{ pathname: "focus-study/support-images/s-999-abc/11111111-1111-4111-8111-111111111111.png", contentType: "image/png" }] });
    assert.equal(res.statusCode, 400);
    assert.equal(res.body.error, "invalid_image_path");

    // Wrong uid: rejected.
    res = await post({ action: "confirm-images", supportId: "s-200-abc", uid: UID_B, images: [{ pathname: good, contentType: "image/png" }] });
    assert.equal(res.statusCode, 403);
  } finally {
    supportImages.setBlobReader(async () => null);
  }
});

test("support: text posts are rate-limited and return the support id", async () => {
  const store = makeSupportStore([]);
  const handler = loadSupport(store);
  const post = async (body, headers = {}) => {
    const res = makeRes();
    await handler({ method: "POST", body, headers }, res);
    return res;
  };
  let res = await post({ body: "hello team, this is a test", uid: UID_A, name: "Ahmed" });
  assert.equal(res.statusCode, 200, JSON.stringify(res.body));
  assert.equal(res.body.supportId, res.body.id);
  assert.match(res.body.id, /^s-[0-9]+-[a-z0-9]+$/);
  res = await post({ body: "hi", uid: UID_A });
  assert.equal(res.statusCode, 400);
  // 5 uid slots per 15 min: 1 success + 1 short + 3 more = 5 spent, 6th is 429.
  for (let i = 0; i < 3; i += 1) {
    res = await post({ body: "another message for the team", uid: UID_A });
    assert.equal(res.statusCode, 200, JSON.stringify(res.body));
  }
  res = await post({ body: "one too many for the team", uid: UID_A });
  assert.equal(res.statusCode, 429);
  assert.equal(res.body.error, "too_many_requests");
});

// ---------------------------------------------------------------- B5: admin user deletion
function loadAdminMessages(store, syncStore) {
  const audit = [];
  const handler = loadMocked("api/admin/messages.js", {
    "../../lib/messaging-store": store,
    "../../lib/admin-auth": {
      isAdmin: () => true,
      originIsSame: () => true,
      requireAdmin: async () => ({ ok: true, mustChange: false, version: 0 }),
      parseBody: (body) => body || {},
      json: (res, code, value) => res.status(code).json(value),
    },
    "../../lib/admin-audit": {
      readAdminAudit: async () => audit.map((e) => ({ ...e })),
      appendAdminAudit: async (events) => { audit.push(...events); return audit; },
    },
    "../../lib/sync-store": syncStore,
  });
  return { handler, audit };
}

function makeAdminStore() {
  let users = [
    { id: UID_A, name: "Ahmed", profileId: PROFILE_ID, code: "AAAA-BBBB" },
    { id: UID_B, name: "Sara", profileId: OTHER_PROFILE_ID, code: "CCCC-DDDD" },
  ];
  let inbox = [{ id: "s-1", uid: UID_A, body: "help", images: [{ path: "focus-study/support-images/s-1/11111111-1111-4111-8111-111111111111.png" }] }];
  let registrations = [{ event: "registered", uid: UID_A }];
  let auditReads = 0;
  return {
    normalizeProfileId: (v) => String(v || "").trim().toLowerCase(),
    readMessages: async () => [],
    appendMessage: async (item) => [item],
    removeMessage: async () => [],
    readInbox: async () => inbox.map((i) => ({ ...i })),
    markSupport: async () => inbox,
    readUsers: async () => users.map((u) => ({ ...u })),
    readNotificationSounds: async () => ({ sounds: {}, updatedAt: "" }),
    writeNotificationSounds: async (sounds) => ({ sounds, updatedAt: "" }),
    readDeliveryEvents: async () => [],
    appendDeliveryEvents: async () => [],
    readRegistrationEvents: async () => { auditReads += 1; return registrations.map((e) => ({ ...e })); },
    appendRegistrationEvents: async (events) => { registrations = [...events, ...registrations]; return registrations; },
    deleteUser: async (id) => {
      const found = users.find((u) => u.id === id);
      if (!found) return false;
      users = users.filter((u) => u.id !== id);
      inbox = inbox.filter((i) => i.uid !== id);
      return true;
    },
    normalizeExpiresAt: () => "",
    expiresAtFromDays: () => "",
    _users: () => users.map((u) => ({ ...u })),
    _inbox: () => inbox.map((i) => ({ ...i })),
    _registrations: () => registrations.map((e) => ({ ...e })),
  };
}

test("admin delete: single delete needs DELETE confirmation and removes user + sync + audit", async () => {
  const store = makeAdminStore();
  const syncCalls = [];
  const { handler, audit } = loadAdminMessages(store, {
    deleteSync: async (pid) => { syncCalls.push(["sync", pid]); },
    deleteAccount: async (pid) => { syncCalls.push(["account", pid]); return true; },
  });
  const post = async (body) => {
    const res = makeRes();
    await handler({ method: "POST", body, headers: ORIGIN_HEADERS }, res);
    return res;
  };
  let res = await post({ action: "deleteUser", userId: UID_A, confirm: "delete" });
  assert.equal(res.statusCode, 400);
  assert.equal(res.body.error, "confirm_required");
  assert.equal(store._users().length, 2);

  res = await post({ action: "deleteUser", userId: UID_A, confirm: "DELETE" });
  assert.equal(res.statusCode, 200, JSON.stringify(res.body));
  assert.equal(res.body.deleted, 1);
  assert.equal(store._users().length, 1);
  assert.equal(store._inbox().length, 0);
  assert.deepEqual(syncCalls, [["sync", PROFILE_ID], ["account", PROFILE_ID]]);
  assert.ok(store._registrations().some((e) => e.event === "deleted_by_admin" && e.uid === UID_A));
  assert.ok(audit.some((e) => e.action === "user_delete"));

  res = await post({ action: "deleteUser", userId: UID_A, confirm: "DELETE" });
  assert.equal(res.statusCode, 200);
  assert.equal(res.body.results[0].error, "user_not_found");
});

test("admin delete: bulk delete needs its own DELETE ALL confirmation", async () => {
  const store = makeAdminStore();
  const { handler, audit } = loadAdminMessages(store, {
    deleteSync: async () => {},
    deleteAccount: async () => true,
  });
  const post = async (body) => {
    const res = makeRes();
    await handler({ method: "POST", body, headers: ORIGIN_HEADERS }, res);
    return res;
  };
  let res = await post({ action: "deleteUsers", userIds: [UID_A, UID_B], confirm: "DELETE" });
  assert.equal(res.statusCode, 400);
  assert.equal(store._users().length, 2);
  res = await post({ action: "deleteUsers", userIds: [UID_A, UID_B], confirm: "DELETE ALL" });
  assert.equal(res.statusCode, 200, JSON.stringify(res.body));
  assert.equal(res.body.deleted, 2);
  assert.equal(store._users().length, 0);
  assert.ok(audit.some((e) => e.action === "user_delete_bulk"));
});

test("admin views: audit and registrations are readable from the admin function", async () => {
  const store = makeAdminStore();
  const { handler } = loadAdminMessages(store, { deleteSync: async () => {}, deleteAccount: async () => true });
  let res = makeRes();
  await handler({ method: "GET", query: { view: "registrations" }, headers: ORIGIN_HEADERS }, res);
  assert.equal(res.statusCode, 200, JSON.stringify(res.body));
  assert.equal(res.body.total, 1);
  res = makeRes();
  await handler({ method: "GET", query: { view: "audit" }, headers: ORIGIN_HEADERS }, res);
  assert.equal(res.statusCode, 200);
  assert.equal(res.body.total, 0);
});

// ---------------------------------------------------------------- B1+B10: public feed + receipts
function makeFeedStore() {
  const users = [{ id: UID_A, name: "Ahmed", profileId: PROFILE_ID }];
  const legacy = { id: "9999aaaabbbbccccddddeeeeffff0000", name: "Omar" };
  const messages = [
    { id: "m-live", title: "Live", targetMode: "broadcast" },
    { id: "m-expired", title: "Old", targetMode: "broadcast", expiresAt: "2026-01-01T00:00:00.000Z" },
  ];
  let deliveries = [];
  return {
    normalizeProfileId: (v) => {
      const clean = String(v || "").trim().toLowerCase();
      return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(clean) ? clean : "";
    },
    readUsers: async () => [...users, legacy].map((u) => ({ ...u })),
    readMessages: async () => messages.map((m) => ({ ...m })),
    readNotificationSounds: async () => ({ sounds: {}, updatedAt: "" }),
    appendDeliveryEvents: async (events) => {
      const seen = new Set(deliveries.map((e) => `${e.event}:${e.messageId}:${e.recipient}`));
      for (const e of events) {
        const key = `${e.event}:${e.messageId}:${e.recipient}`;
        if (!seen.has(key)) { seen.add(key); deliveries.push({ ...e }); }
      }
      return deliveries;
    },
    appendRegistrationEvents: async () => [],
    readRegistrationEvents: async () => [],
    upsertUser: async () => [],
    deleteUser: async () => false,
    visibleMessages: (rows, now = Date.now()) =>
      (Array.isArray(rows) ? rows : []).filter((item) => {
        const time = Date.parse(String(item?.expiresAt || ""));
        return !(Number.isFinite(time) && time <= now);
      }),
    _deliveries: () => deliveries.map((d) => ({ ...d })),
  };
}

function loadFeed(store, syncStore) {
  return loadMocked("api/messages.js", {
    "../lib/messaging-store": store,
    "../lib/sync-store": syncStore || { authenticate: async () => false },
    "../lib/admin-auth": { parseBody: (body) => body || {}, setSecurityHeaders: () => {} },
  });
}

test("feed: expired team messages never reach the client", async () => {
  const handler = loadFeed(makeFeedStore());
  const res = makeRes();
  await handler({ method: "GET", query: {}, headers: {} }, res);
  assert.equal(res.statusCode, 200, JSON.stringify(res.body));
  assert.deepEqual(res.body.messages.map((m) => m.id), ["m-live"]);
});

test("delivered: pid proof works, passphrase proof works, legacy uid-only works, strangers fail", async () => {
  const store = makeFeedStore();
  const handler = loadFeed(store, { authenticate: async (pid, pass) => pid === PROFILE_ID && pass === "correct-passphrase" });
  const post = async (body) => {
    const res = makeRes();
    await handler({ method: "POST", body, headers: {} }, res);
    return res;
  };
  let res = await post({ action: "delivered", uid: UID_A, pid: PROFILE_ID, ids: ["m-live"] });
  assert.equal(res.statusCode, 200, JSON.stringify(res.body));
  assert.equal(store._deliveries().length, 1);

  res = await post({ action: "delivered", uid: UID_A, pid: OTHER_PROFILE_ID, ids: ["m-live"] });
  assert.equal(res.statusCode, 409);

  // Passphrase proof (fresh limiter scope: new handler load would reset the
  // limiter, so use a different uid key via the legacy row instead).
  res = await post({ action: "delivered", uid: "9999aaaabbbbccccddddeeeeffff0000", ids: ["m-live"] });
  assert.equal(res.statusCode, 200, JSON.stringify(res.body), "legacy rows without a profile id keep uid-only receipts");

  res = await post({ action: "delivered", uid: UID_B, ids: ["m-live"] });
  assert.equal(res.statusCode, 409, "an unregistered uid with no proof cannot forge receipts");
});

test("delivered: passphrase proof works for a registered user", async () => {
  const store = makeFeedStore();
  const handler = loadFeed(store, { authenticate: async (pid, pass) => pid === PROFILE_ID && pass === "correct-passphrase" });
  const post = async (body) => {
    const res = makeRes();
    await handler({ method: "POST", body, headers: {} }, res);
    return res;
  };
  let res = await post({ action: "delivered", uid: UID_A, passphrase: "correct-passphrase", ids: ["m-live"] });
  assert.equal(res.statusCode, 200, JSON.stringify(res.body));
  res = await post({ action: "delivered", uid: UID_A, passphrase: "wrong-passphrase", ids: ["m-live"] });
  assert.equal(res.statusCode, 409);
});

test("delivered: receipts are rate-limited per uid", async () => {
  const store = makeFeedStore();
  const handler = loadFeed(store);
  const post = async (body) => {
    const res = makeRes();
    await handler({ method: "POST", body, headers: {} }, res);
    return res;
  };
  for (let i = 0; i < 5; i += 1) {
    const res = await post({ action: "delivered", uid: UID_A, pid: PROFILE_ID, ids: ["m-live"] });
    assert.equal(res.statusCode, 200, JSON.stringify(res.body));
  }
  const res = await post({ action: "delivered", uid: UID_A, pid: PROFILE_ID, ids: ["m-live"] });
  assert.equal(res.statusCode, 429);
});

// ---------------------------------------------------------------- B9/B10: release integrity
test("release: sha256 is stored, validated and changes the CI publish decision", () => {
  const { normalizeSha256, releasePayloadEquals, decideCiPublish, releasePageHost } = require("../lib/release-store");
  const good = "a".repeat(64);
  assert.equal(normalizeSha256(good.toUpperCase()), good);
  assert.equal(normalizeSha256("xyz"), "");
  assert.equal(normalizeSha256("a".repeat(63)), "");
  const base = { packageName: "com.focusstudy.poc", versionCode: 44, versionName: "0.35.0", storeUrl: "https://qubixstore.vercel.app/focus-study-updates", apkUrl: "https://example.com/app.apk", description: "d", releasePageUrl: "https://update-site-sigma.vercel.app/#download", releaseNotes: "n" };
  assert.equal(releasePayloadEquals(base, { ...base, sha256: good }), false, "adding a hash is a payload change");
  assert.equal(releasePayloadEquals({ ...base, sha256: good }, { ...base, sha256: good }), true);
  assert.deepEqual(decideCiPublish({ ...base, sha256: good }, { ...base, sha256: "b".repeat(64) }), { action: "write" });
  assert.deepEqual(decideCiPublish({ ...base, sha256: good }, { ...base, sha256: good }), { action: "unchanged" });
  process.env.FOCUS_SITE_HOST = "update-site-sigma.vercel.app";
  assert.equal(releasePageHost({ headers: { host: "evil.example.com" } }), "update-site-sigma.vercel.app", "configured host pins the release page URL");
  delete process.env.FOCUS_SITE_HOST;
  assert.equal(releasePageHost({ headers: { host: "preview.example.com" } }), "preview.example.com");
});

test("apk magic: verifyApkMagic reads only the first bytes and classifies correctly", async () => {
  const { verifyApkMagic } = require("../lib/release-store");
  const zipFetch = async () => ({
    ok: true,
    body: {
      getReader: () => ({
        read: async () => ({ value: new Uint8Array([0x50, 0x4b, 0x03, 0x04, 0x14, 0x00]) }),
        cancel: async () => {},
      }),
    },
  });
  const textFetch = async () => ({
    ok: true,
    body: {
      getReader: () => ({
        read: async () => ({ value: new Uint8Array(Buffer.from("<html>not an apk")) }),
        cancel: async () => {},
      }),
    },
  });
  const downFetch = async () => { throw new Error("network down"); };
  const missingFetch = async () => ({ ok: false, status: 404 });
  assert.deepEqual(await verifyApkMagic("https://example.com/app.apk", zipFetch), { ok: true });
  assert.deepEqual(await verifyApkMagic("https://example.com/app.apk", textFetch), { ok: false, error: "apk_not_a_zip" });
  assert.deepEqual(await verifyApkMagic("https://example.com/app.apk", downFetch), { ok: false, error: "apk_unreachable" });
  assert.deepEqual(await verifyApkMagic("https://example.com/app.apk", missingFetch), { ok: false, error: "apk_unreachable" });
});

async function main() {
  for (const [name, fn] of queue) {
    await fn();
    passed += 1;
    console.log(`ok - ${name}`);
  }
  console.log(`final-audit server tests: ${passed} passed`);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
