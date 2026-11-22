/** Session status and logout now share one function; the old /api/admin/logout URL is rewritten. */
const assert = require("node:assert/strict");
const path = require("node:path");

const ROOT = path.join(__dirname, "..");
const abs = path.join(ROOT, "api/admin/session.js");

function loadHandler(auth, credentials) {
  const authDep = require.resolve(path.join(ROOT, "lib/admin-auth.js"));
  const credDep = require.resolve(path.join(ROOT, "lib/admin-credentials.js"));
  const saved = [require.cache[authDep], require.cache[credDep]];
  require.cache[authDep] = { id: authDep, filename: authDep, loaded: true, exports: auth, children: [], paths: [] };
  require.cache[credDep] = { id: credDep, filename: credDep, loaded: true, exports: credentials, children: [], paths: [] };
  delete require.cache[abs];
  try {
    return require(abs);
  } finally {
    require.cache[authDep] = saved[0];
    require.cache[credDep] = saved[1];
    delete require.cache[abs];
  }
}

function makeRes() {
  return {
    headers: {}, statusCode: 200, body: null,
    setHeader(k, v) { this.headers[k] = v; return this; },
    status(code) { this.statusCode = code; return this; },
    json(value) { this.body = value; return this; },
  };
}

let passed = 0;
async function test(name, fn) {
  await fn();
  passed += 1;
  console.log(`ok - ${name}`);
}

async function main() {
  process.env.ADMIN_USERNAME = "tester";
  process.env.ADMIN_SESSION_SECRET = "not-a-real-secret";
  let cleared = 0;
  let originOk = true;
  const auth = {
    isAdmin: () => true,
    originIsSame: () => originOk,
    clearSessionCookie: () => { cleared += 1; },
    json: (res, status, data) => res.status(status).json(data),
  };
  const credentials = { mustChangePassword: async () => false };
  const handler = loadHandler(auth, credentials);

  await test("GET reports the session", async () => {
    const res = makeRes();
    await handler({ method: "GET" }, res);
    assert.equal(res.statusCode, 200);
    assert.equal(res.body.authenticated, true);
    assert.equal(res.body.username, "tester");
  });

  await test("POST logs out through the shared function", async () => {
    const res = makeRes();
    await handler({ method: "POST" }, res);
    assert.equal(res.statusCode, 200);
    assert.equal(res.body.ok, true);
    assert.equal(cleared, 1);
  });

  await test("logout still rejects a cross-origin request", async () => {
    originOk = false;
    const res = makeRes();
    await handler({ method: "POST" }, res);
    assert.equal(res.statusCode, 403);
    assert.equal(cleared, 1);
    originOk = true;
  });

  await test("unsupported methods are rejected", async () => {
    const res = makeRes();
    await handler({ method: "PUT" }, res);
    assert.equal(res.statusCode, 405);
  });

  console.log(`admin session tests: ${passed} passed`);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
