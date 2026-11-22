/**
 * Local regression test for explicit target modes and the delivery ledger.
 *
 * It mocks the Blob-backed store and admin auth, so no token, network or database is involved:
 *   npm run test:targeting
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

function deliveryKey(event) {
  const messageId = String(event.messageId || "").trim();
  if (!messageId) return "";
  if (event.event === "delivered") return `delivered:${messageId}:${String(event.recipient || "")}`;
  return `published:${messageId}`;
}

function makeStore(users, messages) {
  let currentUsers = users.map((item) => ({ ...item }));
  let currentMessages = messages.map((item) => ({ ...item }));
  let currentDeliveries = [];
  const generated = [
    "11111111-1111-4111-8111-111111111111",
    "22222222-2222-4222-8222-222222222222",
    "33333333-3333-4333-8333-333333333333",
  ];
  let issued = 0;

  return {
    normalizeProfileId,
    readUsers: async () => currentUsers.map((item) => ({ ...item })),
    readMessages: async () => currentMessages.map((item) => ({ ...item })),
    appendMessage: async (item) => {
      currentMessages = [item, ...currentMessages];
      return currentMessages.map((entry) => ({ ...entry }));
    },
    readNotificationSounds: async () => ({ sounds: {}, updatedAt: "" }),
    upsertUser: async (entry) => {
      const existing = currentUsers.find((item) => item.id === entry.id);
      const suggested = normalizeProfileId(entry.profileId);
      const takenByOther = Boolean(suggested) && currentUsers.some((item) =>
        item.id !== entry.id && normalizeProfileId(item.profileId) === suggested
      );
      const profileId = normalizeProfileId(existing && existing.profileId)
        || (suggested && !takenByOther ? suggested : generated[issued++]);
      const record = { ...entry, profileId };
      currentUsers = existing
        ? currentUsers.map((item) => (item.id === entry.id ? { ...item, ...record } : item))
        : [record, ...currentUsers];
      return currentUsers.map((item) => ({ ...item }));
    },
    readDeliveryEvents: async () => currentDeliveries.map((item) => ({ ...item })),
    appendDeliveryEvents: async (events) => {
      const seen = new Set(currentDeliveries.map(deliveryKey));
      const added = [];
      for (const event of events) {
        const key = deliveryKey(event);
        if (!key || seen.has(key)) continue;
        seen.add(key);
        added.push({ ...event, key, id: `d-${added.length + 1}` });
      }
      currentDeliveries = [...added, ...currentDeliveries];
      return currentDeliveries.map((item) => ({ ...item }));
    },
    _addMessage: (item) => { currentMessages = [item, ...currentMessages]; },
    _users: () => currentUsers.map((item) => ({ ...item })),
    _messages: () => currentMessages.map((item) => ({ ...item })),
    _deliveries: () => currentDeliveries.map((item) => ({ ...item })),
  };
}

const FULL_ID = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6";
const CODE = "A1B2-C3D4";
const PROFILE_ID = "7f3c1b9e-2d4a-4f6b-8c0e-1a2b3c4d5e6f";
const OTHER_PROFILE_ID = "8a4d2c0f-3b5e-4a7c-9d1e-2f3b4c5d6e7f";
const LEGACY_ID = "9999aaaabbbbccccddddeeeeffff0000";
const LEGACY_CODE = "9999-AAAA";
const NEW_ID = "1234567890abcdef1234567890abcdef";

function loadAdminHandler(store, isAdmin = true) {
  return loadMocked("api/admin/messages.js", {
    "../../lib/messaging-store": store,
    "../../lib/admin-auth": {
      isAdmin: () => isAdmin,
      parseBody: (body) => body || {},
      json: (res, code, value) => res.status(code).json(value),
    },
  });
}

function loadMessagesHandler(store) {
  return loadMocked("api/messages.js", {
    "../lib/messaging-store": store,
    "../lib/admin-auth": { parseBody: (body) => body || {} },
  });
}

async function postAdmin(adminHandler, body) {
  const res = makeRes();
  await adminHandler({ method: "POST", query: {}, body }, res);
  return res;
}

async function testRegistration() {
  const store = makeStore([], []);
  const handler = loadMessagesHandler(store);

  let res = makeRes();
  await handler({
    method: "POST",
    body: {
      action: "register", uid: NEW_ID, name: "سارة", profile: "professional",
      versionName: "0.30.0", versionCode: 38, pushToken: "MUST_NOT_BE_STORED",
    },
  }, res);
  assert.equal(res.statusCode, 200, JSON.stringify(res.body));
  assert.match(res.body.profileId, PROFILE_ID_PATTERN, "server must issue a profile id");
  const first = store._users().find((item) => item.id === NEW_ID);
  assert.equal(first.profileId, res.body.profileId, "issued id must be persisted");
  assert.equal(first.name, "سارة");
  assert.equal(first.pushToken, undefined, "push tokens are never stored");
  console.log("✓ registration issues and persists a server-side profile id, without push token storage");

  res = makeRes();
  await handler({
    method: "POST",
    body: { action: "register", uid: NEW_ID, name: "سارة", profile: "professional", profileId: OTHER_PROFILE_ID },
  }, res);
  assert.equal(res.body.profileId, first.profileId, "a second registration must not change the profile id");
  assert.equal(store._users().length, 1, "re-registration must not create a duplicate user");
  console.log("✓ repeated registration keeps the same profile id and creates no duplicate");

  res = makeRes();
  await handler({
    method: "POST",
    body: { action: "register", uid: LEGACY_ID, name: "عمر", profile: "student", profileId: first.profileId },
  }, res);
  assert.equal(res.body.profileId, "22222222-2222-4222-8222-222222222222",
    "a suggested id already used by another install must be rejected");
  assert.equal(store._users().length, 2);
  console.log("✓ a client cannot take another install's profile id");
}

async function testAdmin() {
  const store = makeStore(
    [
      { id: FULL_ID, name: "أحمد", code: CODE, profileId: PROFILE_ID, profile: "student" },
      { id: LEGACY_ID, name: "عمر", code: LEGACY_CODE, profile: "student" },
    ],
    []
  );
  const admin = loadAdminHandler(store);

  let res = await postAdmin(admin, {
    title: "رسالة خاصة", body: "مرحبا أحمد", targetMode: "person", targetRef: CODE,
    audienceProfile: "graduate", minVersionCode: 900,
  });
  assert.equal(res.statusCode, 200, JSON.stringify(res.body));
  const published = store._messages()[0];
  assert.equal(published.targetMode, "person");
  assert.equal(published.targetProfileId, PROFILE_ID, "code must resolve to the server-issued profile id");
  assert.equal(published.targetUserId, FULL_ID, "legacy install id stays for 0.29.0 builds");
  assert.equal(published.targetCode, CODE, "short code stays only as a dashboard label");
  assert.equal(published.audienceProfile, "", "person mode must drop conflicting filters");
  assert.equal(published.minVersionCode, 0);
  assert.equal(store._deliveries()[0].event, "published");
  assert.equal(store._deliveries()[0].messageId, published.id);
  console.log("✓ explicit person mode resolves the code and ignores conflicting filters");

  res = await postAdmin(admin, {
    title: "رسالة", body: "نص اختبار", targetMode: "person", targetRef: PROFILE_ID,
  });
  assert.equal(res.statusCode, 200, JSON.stringify(res.body));
  assert.equal(store._messages()[0].targetProfileId, PROFILE_ID, "a pasted profile id is accepted");
  console.log("✓ admin can also target a full profile id from the user registry");

  res = await postAdmin(admin, {
    title: "رسالة", body: "نص اختبار", targetMode: "person", targetRef: FULL_ID,
  });
  assert.equal(res.statusCode, 200, JSON.stringify(res.body));
  assert.equal(store._messages()[0].targetProfileId, PROFILE_ID, "a full install id resolves via the registry");
  console.log("✓ a full install id resolves to the same profile through the registry");

  res = await postAdmin(admin, {
    title: "رسالة", body: "نص اختبار", targetMode: "person", targetRef: "ZZZZ-ZZZZ",
  });
  assert.equal(res.statusCode, 404, JSON.stringify(res.body));
  assert.equal(res.body.error, "target_user_not_found");
  console.log("✓ an unregistered short code is rejected");

  res = await postAdmin(admin, {
    title: "رسالة", body: "نص اختبار", targetMode: "person", targetRef: LEGACY_CODE,
  });
  assert.equal(res.statusCode, 409, JSON.stringify(res.body));
  assert.equal(res.body.error, "target_profile_not_ready");
  console.log("✓ a registry row without a profile id is never turned into a broadcast");

  const conflicts = [
    [{ targetMode: "broadcast", targetRef: CODE }, "mode_field_conflict"],
    [{ targetMode: "broadcast", audienceProfile: "student" }, "mode_field_conflict"],
    [{ targetMode: "audience" }, "audience_required"],
    [{ targetMode: "person" }, "target_required"],
    [{ targetMode: "unknown" }, "invalid_target_mode"],
  ];
  for (const [extra, error] of conflicts) {
    res = await postAdmin(admin, { title: "رسالة", body: "نص اختبار", ...extra });
    assert.equal(res.statusCode, 400, JSON.stringify(res.body));
    assert.equal(res.body.error, error, `${error} must be enforced`);
  }
  console.log("✓ invalid mode combinations are rejected before publishing");

  res = await postAdmin(admin, {
    title: "رسالة للطلاب", body: "نص اختبار", targetMode: "audience", audienceProfile: "student", minVersionCode: 35,
  });
  assert.equal(res.statusCode, 200, JSON.stringify(res.body));
  assert.equal(store._messages()[0].targetMode, "audience");
  assert.equal(store._messages()[0].targetProfileId, "");
  console.log("✓ explicit audience mode publishes without a personal address");

  res = await postAdmin(admin, {
    title: "إعلان عام", body: "نص اختبار", targetMode: "broadcast",
  });
  assert.equal(res.statusCode, 200, JSON.stringify(res.body));
  assert.equal(store._messages()[0].targetMode, "broadcast");
  assert.equal(store._messages()[0].audienceProfile, "");
  console.log("✓ explicit broadcast mode publishes without filters or targets");

  const denied = loadAdminHandler(store, false);
  res = makeRes();
  await denied({ method: "GET", query: { view: "deliveries" } }, res);
  assert.equal(res.statusCode, 401, JSON.stringify(res.body));
  assert.equal(res.body.error, "authentication_required");
  res = await postAdmin(denied, { title: "رسالة", body: "نص اختبار", targetMode: "broadcast" });
  assert.equal(res.statusCode, 401, JSON.stringify(res.body));
  console.log("✓ unauthorised callers can neither read the ledger nor publish");

  res = makeRes();
  await admin({ method: "GET", query: { view: "deliveries" } }, res);
  assert.equal(res.statusCode, 200);
  assert.equal(res.body.events.length, store._deliveries().length);
  console.log("✓ the private delivery ledger is readable from the existing admin function");
}

async function testFeedAndReceipts() {
  const store = makeStore(
    [{ id: FULL_ID, name: "أحمد", code: CODE, profileId: PROFILE_ID, profile: "student" }],
    [
      {
        id: "m-person", title: "خاص", body: "لأحمد", targetMode: "person",
        targetProfileId: PROFILE_ID, targetUserId: FULL_ID, targetCode: CODE,
      },
      {
        id: "m-legacy-person", title: "خاص قديم", body: "لأحمد", targetUserId: FULL_ID,
        audienceProfile: "student",
      },
      {
        id: "m-audience", title: "للطلاب", body: "من إصدار 35", targetMode: "audience",
        audienceProfile: "student", minVersionCode: 35,
      },
      {
        id: "m-code-only", title: "رمز قديم", body: "لا يُعرض", targetCode: CODE,
        audienceProfile: "student",
      },
      {
        id: "m-broadcast", title: "إعلان", body: "للجميع", targetMode: "broadcast",
      },
    ]
  );
  const handler = loadMessagesHandler(store);
  const feed = async (query) => {
    const response = makeRes();
    await handler({ method: "GET", query: { profile: "student", version: "37", ...query } }, response);
    return response;
  };
  const ids = (response) => response.body.messages.map((item) => item.id).sort();

  const mine = await feed({ pid: PROFILE_ID, uid: FULL_ID });
  assert.deepEqual(ids(mine), ["m-audience", "m-broadcast", "m-legacy-person", "m-person"]);
  assert.equal(mine.headers["Cache-Control"], "private, no-store");
  console.log("✓ new and legacy identities read their own messages; code-only rows stay hidden");

  const legacyApp = await feed({ uid: FULL_ID });
  assert.deepEqual(ids(legacyApp), ["m-audience", "m-broadcast", "m-legacy-person", "m-person"]);
  console.log("✓ a 0.29.0 build without pid still receives its targeted messages");

  const wrongPerson = await feed({ pid: OTHER_PROFILE_ID });
  assert.deepEqual(ids(wrongPerson), ["m-audience", "m-broadcast"],
    "wrong profile must not fall through to the person messages");
  console.log("✓ wrong profile sees only audience/broadcast rows, never the personal ones");

  const graduate = await feed({ pid: PROFILE_ID, profile: "graduate" });
  assert.deepEqual(ids(graduate), ["m-broadcast", "m-person"],
    "student audience must not reach graduates");
  console.log("✓ audience messages are isolated by profile");

  const oldBuild = await feed({ profile: "student", version: "34" });
  assert.deepEqual(ids(oldBuild), ["m-broadcast"], "min version must hide the audience row");
  const anonymous = await feed({});
  assert.deepEqual(ids(anonymous), ["m-audience", "m-broadcast"]);
  assert.equal(anonymous.headers["Cache-Control"], "public, s-maxage=60, stale-while-revalidate=600");
  console.log("✓ version filtering works and anonymous callers see broadcasts only");

  const receipt = async (body) => {
    const res = makeRes();
    await handler({ method: "POST", body }, res);
    return res;
  };
  let res = await receipt({ action: "delivered", pid: PROFILE_ID, uid: FULL_ID, ids: ["m-person", "m-person", "m-unknown"] });
  assert.equal(res.statusCode, 200, JSON.stringify(res.body));
  assert.equal(store._deliveries().length, 1, "duplicate ids and unknown messages must not create rows");
  assert.equal(store._deliveries()[0].event, "delivered");
  assert.equal(store._deliveries()[0].messageId, "m-person");
  assert.equal(store._deliveries()[0].recipient, PROFILE_ID);
  assert.equal(store._deliveries()[0].messageTitle, "خاص");
  console.log("✓ one receipt per message and recipient, with the message title, is recorded");

  res = await receipt({ action: "delivered", pid: PROFILE_ID, uid: FULL_ID, ids: ["m-person"] });
  assert.equal(res.statusCode, 200);
  assert.equal(store._deliveries().length, 1, "a retry must not duplicate the receipt");
  console.log("✓ retrying the same receipt leaves a single ledger row");

  res = await receipt({ action: "delivered", uid: FULL_ID, ids: ["m-person"] });
  assert.equal(res.statusCode, 200);
  assert.equal(store._deliveries().length, 2, "a legacy install is a separate recipient");
  assert.equal(store._deliveries()[0].recipient, FULL_ID);
  console.log("✓ legacy installs are logged as distinct recipients");

  for (const [body, error] of [
    [{ action: "delivered", ids: ["m-person"] }, "invalid_user"],
    [{ action: "delivered", pid: PROFILE_ID, ids: [] }, "invalid_ids"],
    [{ action: "unknown" }, "invalid_action"],
  ]) {
    res = await receipt(body);
    assert.equal(res.statusCode, 400, JSON.stringify(res.body));
    assert.equal(res.body.error, error);
  }
  console.log("✓ malformed receipts and unknown actions are rejected");
}

async function main() {
  await testRegistration();
  await testAdmin();
  await testFeedAndReceipts();
  console.log("ALL_TARGETING_TESTS_PASSED");
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
