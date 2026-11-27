/**
 * Local regression test for H2 registration ownership proof (audit §11.2).
 *
 * The Blob-backed stores are mocked, so no token, network or database is involved:
 *   npm run test:messages
 */
const assert = require("node:assert/strict");
const path = require("node:path");

const ROOT = path.join(__dirname, "..");

function loadMocked(relativeModule, mocks) {
  const abs = path.resolve(ROOT, relativeModule);
  const dir = path.dirname(abs);
  const saved = new Map();
  for (const [specifier, exports] of Object.entries(mocks)) {
    const dep = require.resolve(path.resolve(dir, specifier));
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
    setHeader(key, value) { this.headers[key] = value; return this; },
    status(code) { this.statusCode = code; return this; },
    json(value) { this.body = value; return this; },
  };
}

const PROFILE_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
function normalizeProfileId(value) {
  const clean = String(value || "").trim().toLowerCase();
  return PROFILE_ID_PATTERN.test(clean) ? clean : "";
}

const GENERATED = [
  "11111111-1111-4111-8111-111111111111",
  "22222222-2222-4222-8222-222222222222",
  "33333333-3333-4333-8333-333333333333",
];
const OTHER_PROFILE_ID = "8a4d2c0f-3b5e-4a7c-9d1e-2f3b4c5d6e7f";
const UID_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
const UID_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

function makeStore(initialUsers = []) {
  let currentUsers = initialUsers.map((item) => ({ ...item }));
  let currentRegistrations = [];
  let currentInbox = [];
  let issued = 0;
  return {
    normalizeProfileId,
    readUsers: async () => currentUsers.map((item) => ({ ...item })),
    readMessages: async () => [],
    readNotificationSounds: async () => ({ sounds: {}, updatedAt: "" }),
    appendDeliveryEvents: async () => [],
    upsertUser: async (entry) => {
      const existing = currentUsers.find((item) => item.id === entry.id);
      if (!existing && currentUsers.length >= 1000) return null;
      const profileId = normalizeProfileId(existing && existing.profileId)
        || GENERATED[issued++] || `00000000-0000-4000-8000-${String(issued).padStart(12, "0")}`;
      const record = { ...entry, profileId };
      currentUsers = existing
        ? currentUsers.map((item) => (item.id === entry.id ? { ...item, ...record } : item))
        : [record, ...currentUsers];
      return currentUsers.map((item) => ({ ...item }));
    },
    readRegistrationEvents: async () => currentRegistrations.map((item) => ({ ...item })),
    appendRegistrationEvents: async (events) => {
      currentRegistrations = [...events.map((e) => ({ ...e })), ...currentRegistrations];
      return currentRegistrations.map((item) => ({ ...item }));
    },
    readInbox: async () => currentInbox.map((item) => ({ ...item })),
    deleteUser: async (id) => {
      const existing = currentUsers.find((item) => item.id === id);
      if (!existing) return false;
      currentUsers = currentUsers.filter((item) => item.id !== id);
      currentInbox = currentInbox.filter((item) => String(item.uid || "") !== id);
      return true;
    },
    _users: () => currentUsers.map((item) => ({ ...item })),
    _registrations: () => currentRegistrations.map((item) => ({ ...item })),
    _inbox: () => currentInbox.map((item) => ({ ...item })),
    _addInbox: (item) => { currentInbox = [item, ...currentInbox]; },
  };
}

function loadHandler(store, syncStore) {
  return loadMocked("api/messages.js", {
    "../lib/messaging-store": store,
    "../lib/sync-store": syncStore || { authenticate: async () => false },
    "../lib/admin-auth": { parseBody: (body) => body || {} },
  });
}

async function post(handler, body, headers = {}) {
  const res = makeRes();
  await handler({ method: "POST", body, headers }, res);
  return res;
}

let passed = 0;
async function test(name, fn) {
  await fn();
  passed += 1;
  console.log(`ok - ${name}`);
}

async function main() {
  await test("fresh registration ignores a suggested profile id; the server issues its own", async () => {
    const store = makeStore();
    const handler = loadHandler(store);
    const res = await post(handler, {
      action: "register", uid: UID_A, name: "سارة", profile: "student",
      profileId: OTHER_PROFILE_ID,
    });
    assert.equal(res.statusCode, 200, JSON.stringify(res.body));
    assert.match(res.body.profileId, PROFILE_ID_PATTERN);
    assert.notEqual(res.body.profileId, OTHER_PROFILE_ID);
    assert.equal(store._users()[0].profileId, res.body.profileId);
  });

  await test("re-registration with the stored profile id succeeds (ownership proof)", async () => {
    const store = makeStore();
    const handler = loadHandler(store);
    const first = await post(handler, { action: "register", uid: UID_A, name: "سارة" });
    assert.equal(first.statusCode, 200);
    const second = await post(handler, {
      action: "register", uid: UID_A, name: "سارة", profileId: first.body.profileId,
    });
    assert.equal(second.statusCode, 200, JSON.stringify(second.body));
    assert.equal(second.body.profileId, first.body.profileId);
    assert.equal(store._users().length, 1);
  });

  await test("re-registration with a wrong profile id is rejected without leaking the id", async () => {
    const store = makeStore();
    const handler = loadHandler(store);
    const first = await post(handler, { action: "register", uid: UID_A, name: "سارة" });
    const res = await post(handler, {
      action: "register", uid: UID_A, name: "سارة", profileId: OTHER_PROFILE_ID,
    });
    assert.equal(res.statusCode, 409, JSON.stringify(res.body));
    assert.equal(res.body.error, "already_registered");
    assert.equal(res.body.profileId, undefined);
    assert.equal(store._users().length, 1);
    assert.equal(store._users()[0].profileId, first.body.profileId);
    const audit = store._registrations().find((e) => e.event === "register_rejected");
    assert.ok(audit, "a rejected registration must be audited");
    assert.equal(audit.reason, "no_proof");
  });

  await test("re-registration with the sync passphrase succeeds (ownership proof)", async () => {
    const store = makeStore();
    const syncStore = {
      authenticate: async (pid, pass) => pass === "correct-passphrase",
    };
    const handler = loadHandler(store, syncStore);
    const first = await post(handler, { action: "register", uid: UID_A, name: "سارة" });
    const ok = await post(handler, {
      action: "register", uid: UID_A, name: "سارة",
      profileId: OTHER_PROFILE_ID, passphrase: "correct-passphrase",
    });
    assert.equal(ok.statusCode, 200, JSON.stringify(ok.body));
    assert.equal(ok.body.profileId, first.body.profileId);
    const bad = await post(handler, {
      action: "register", uid: UID_A, name: "سارة",
      profileId: OTHER_PROFILE_ID, passphrase: "wrong-passphrase",
    });
    assert.equal(bad.statusCode, 409);
  });

  await test("registration is rate-limited per install id", async () => {
    const store = makeStore();
    const handler = loadHandler(store);
    const first = await post(handler, { action: "register", uid: UID_A, name: "سارة" });
    assert.equal(first.statusCode, 200);
    // 4 more proof-less attempts: each spends a uid slot (5 per 15 min).
    for (let i = 0; i < 4; i += 1) {
      const res = await post(handler, {
        action: "register", uid: UID_A, name: "سارة", profileId: OTHER_PROFILE_ID,
      });
      assert.equal(res.statusCode, 409);
    }
    const throttled = await post(handler, {
      action: "register", uid: UID_A, name: "سارة", profileId: OTHER_PROFILE_ID,
    });
    assert.equal(throttled.statusCode, 429);
    assert.equal(throttled.body.error, "too_many_requests");
    assert.ok(Number(throttled.headers["Retry-After"]) > 0);
  });

  await test("registration is rate-limited per caller address", async () => {
    const store = makeStore();
    const handler = loadHandler(store);
    const headers = { "x-forwarded-for": "203.0.113.9" };
    for (let i = 0; i < 10; i += 1) {
      const res = await post(handler, {
        action: "register", uid: `fresh-uid-${i}-abcdef`, name: "مستخدم",
      }, headers);
      assert.equal(res.statusCode, 200, JSON.stringify(res.body));
    }
    const throttled = await post(handler, {
      action: "register", uid: "fresh-uid-10-abcdef", name: "مستخدم",
    }, headers);
    assert.equal(throttled.statusCode, 429);
  });

  await test("a full registry refuses a new install instead of evicting the oldest", async () => {
    const existing = Array.from({ length: 1000 }, (_, i) => ({
      id: `user-${i}-abcdef`, profileId: `00000000-0000-4000-8000-${String(i).padStart(12, "0")}`,
    }));
    const store = makeStore(existing);
    const handler = loadHandler(store);
    const refused = await post(handler, { action: "register", uid: UID_A, name: "جديد" });
    assert.equal(refused.statusCode, 503, JSON.stringify(refused.body));
    assert.equal(refused.body.error, "registry_full");
    assert.equal(store._users().length, 1000);
    assert.equal(store._users()[999].id, "user-999-abcdef", "the oldest user must survive");
    // Re-registering an existing install still works when full.
    const existingUser = store._users()[0];
    const ok = await post(handler, {
      action: "register", uid: existingUser.id, name: "قديم",
      profileId: existingUser.profileId,
    });
    assert.equal(ok.statusCode, 200, JSON.stringify(ok.body));
    assert.equal(store._users().length, 1000);
  });

  await test("delete removes the user row and its support messages (P-02)", async () => {
    const store = makeStore();
    const handler = loadHandler(store);
    const first = await post(handler, { action: "register", uid: UID_A, name: "سارة" });
    assert.equal(first.statusCode, 200);
    store._addInbox({ id: "s-1", uid: UID_A, body: "شكوى من سارة" });
    store._addInbox({ id: "s-2", uid: UID_B, body: "شكوى من آخر" });
    // Without proof: rejected, nothing deleted.
    const noProof = await post(handler, {
      action: "delete", uid: UID_A, profileId: OTHER_PROFILE_ID,
    });
    assert.equal(noProof.statusCode, 409);
    assert.equal(store._users().length, 1);
    assert.equal(store._inbox().length, 2);
    // With the stored profile id: deleted (user row + own support messages only).
    const deleted = await post(handler, {
      action: "delete", uid: UID_A, profileId: first.body.profileId,
    });
    assert.equal(deleted.statusCode, 200, JSON.stringify(deleted.body));
    assert.equal(deleted.body.deleted, true);
    assert.equal(store._users().length, 0);
    assert.equal(store._inbox().length, 1);
    assert.equal(store._inbox()[0].id, "s-2");
    // Deleting a never-registered uid is a no-op success.
    const missing = await post(handler, { action: "delete", uid: UID_B });
    assert.equal(missing.statusCode, 200);
    assert.equal(missing.body.deleted, false);
  });

  console.log(`messages tests: ${passed} passed`);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
