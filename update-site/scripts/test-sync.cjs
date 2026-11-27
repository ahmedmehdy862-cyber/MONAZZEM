/**
 * Local regression test for the Phase 6 sync store and API.
 *
 * The Blob-backed store is mocked, so no token, network or database is involved:
 *   npm run test:sync
 */
const assert = require("node:assert/strict");
const path = require("node:path");

const ROOT = path.join(__dirname, "..");
const real = require("../lib/sync-store");
const realRate = require("../lib/sync-rate-limit");

const PID_A = "11111111-1111-4111-8111-111111111111";
const PID_B = "22222222-2222-4222-8222-222222222222";
const UNKNOWN = "33333333-3333-4333-8333-333333333333";
const PASS_A = "كلمة مرور قوية 123";
const PASS_B = "passphrase for account b";

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

function makeMockStore() {
  const accounts = new Map();
  const syncs = new Map();
  return {
    normalizeProfileId: real.normalizeProfileId,
    validatePassphrase: real.validatePassphrase,
    hasAccount: async (pid) => accounts.has(pid),
    authenticate: async (pid, pass) => accounts.get(pid) === pass,
    setAccount: async (pid, pass) => { accounts.set(pid, pass); },
    stripEncrypted: real.stripEncrypted,
    deleteSync: async (pid) => { syncs.delete(pid); },
    deleteAccount: async (pid) => accounts.delete(pid),
    readSync: async (pid, includeEncrypted = true) => {
      const record = syncs.get(pid) || {
        revision: 0, updatedAt: 0, data: real.normalizeSyncPayload({}),
      };
      return includeEncrypted ? record : real.stripEncrypted(record);
    },
    writeSync: async (pid, record) => { syncs.set(pid, record); },
    mergeSync: real.mergeSync,
    _accounts: accounts,
    _syncs: syncs,
  };
}

function makeHandler(store, limiter = realRate.createLimiter()) {
  return loadMocked("api/sync.js", {
    "../lib/sync-store": store,
    "../lib/messaging-store": {
      readUsers: async () => [
        { id: "uid-a", profileId: PID_A },
        { id: "uid-b", profileId: PID_B },
      ],
    },
    "../lib/sync-rate-limit": { limiter },
  });
}

function post(handler, body, headers = {}) {
  const res = makeRes();
  return Promise.resolve(handler({ method: "POST", body, headers }, res)).then(() => res);
}

let passed = 0;
async function test(name, fn) {
  await fn();
  passed += 1;
  console.log(`ok - ${name}`);
}

async function main() {
  await test("passphrase has a minimum length and rejects blanks", async () => {
    assert.equal(real.validatePassphrase("   "), "");
    assert.equal(real.validatePassphrase("short"), "");
    assert.equal(real.validatePassphrase(PASS_A), PASS_A);
  });

  await test("scrypt hash verifies the right passphrase and not a wrong one", async () => {
    const { salt, hash } = real.hashPassphrase(PASS_A);
    assert.notEqual(hash, PASS_A);
    assert.equal(real.verifyPassphrase(PASS_A, salt, hash), true);
    assert.equal(real.verifyPassphrase(PASS_B, salt, hash), false);
    assert.equal(real.verifyPassphrase(PASS_A, "", hash), false);
  });

  await test("payload whitelist drops notes, ids, tokens and unknown fields", async () => {
    const clean = real.normalizeSyncPayload({
      tasks: [{
        id: "1", title: "مهمة", notes: "ملاحظة خاصة", minutes: 30, category: "study",
        priority: 3, status: "todo", dueAt: 123, recurrence: "daily", subjectId: "s1",
        typeId: "t1", updatedAt: 1000, deviceId: "d1", uid: "u1", pushToken: "p1",
      }],
      subjects: [{ id: "s1", title: "رياضيات", notes: "سر", updatedAt: 1000 }],
      taskTypes: [{ id: "t1", title: "واجب", archived: true, updatedAt: 1000 }],
      notes: [{ id: "n1", text: "لا تخرج من الجهاز" }],
      messages: [{ id: "m1", title: "رسالة خاصة" }],
    });
    assert.deepEqual(Object.keys(clean).sort(), [
      "calorieLogs", "habitLogs", "habits", "healthLogs", "moodLogs",
      "subjects", "taskTypes", "tasks", "transactions", "wirdLogs",
    ]);
    assert.equal(clean.tasks[0].title, "مهمة");
    assert.equal(clean.tasks[0].notes, undefined);
    assert.equal(clean.tasks[0].deviceId, undefined);
    assert.equal(clean.tasks[0].uid, undefined);
    assert.equal(clean.tasks[0].pushToken, undefined);
    assert.equal(clean.subjects[0].name, "رياضيات");
    assert.equal(clean.subjects[0].notes, undefined);
    assert.equal(clean.taskTypes[0].archived, true);
  });

  await test("life collections whitelist only their contract fields", async () => {
    const clean = real.normalizeSyncPayload({
      habits: [{ id: "3", name: "قراءة", createdAt: 500, archived: true, secret: "x", updatedAt: 100 }],
      habitLogs: [{ habitId: "3", day: "2026-11-26", updatedAt: 100 }],
      habitLogsBad: [{ id: "9", habitId: "3", day: "not-a-day", updatedAt: 100 }],
      wirdLogs: [{ id: "2026-11-26", day: "2026-11-26", pages: 2, surah: "البقرة", loggedAt: 1, updatedAt: 100 }],
      healthLogs: [{ id: "2026-11-26", day: "2026-11-26", weight: 70.5, sleepHours: 7, waterCups: 6, exerciseMinutes: 30, updatedAt: 100 }],
      transactions: [{
        id: "8", kind: "expense", amount: 12.5, category: "طعام", note: "ملاحظة مالية سرية",
        day: "2026-11-26", createdAt: 400, updatedAt: 100,
      }],
      moodLogs: [{ id: "2026-11-26", day: "2026-11-26", mood: 4, updatedAt: 100 }],
      calorieLogs: [{ id: "5", day: "2026-11-26", name: "أرز", calories: 300, createdAt: 400, updatedAt: 100 }],
    });
    assert.equal(clean.habits[0].name, "قراءة");
    assert.equal(clean.habits[0].archived, true);
    assert.equal(clean.habits[0].secret, undefined);
    assert.equal(clean.habitLogs[0].id, "3:2026-11-26");
    assert.equal(clean.habitLogs[0].habitId, "3");
    assert.equal(clean.wirdLogs[0].pages, 2);
    assert.equal(clean.wirdLogs[0].loggedAt, undefined);
    assert.equal(clean.healthLogs[0].weight, 70.5);
    assert.equal(clean.transactions[0].amount, 12.5);
    assert.equal(clean.transactions[0].note, undefined);
    assert.equal(clean.moodLogs[0].mood, 4);
    assert.equal(clean.calorieLogs[0].calories, 300);
  });

  await test("life records reject bad days, ranges and kinds, and done:false becomes a tombstone", async () => {
    const clean = real.normalizeSyncPayload({
      habitLogs: [
        { habitId: "3", day: "2026-11-26", done: false, updatedAt: 100 },
        { habitId: "3", day: "2026-13-40", updatedAt: 100 },
      ],
      wirdLogs: [{ id: "2026-11-26", day: "2026-11-26", pages: 9999, surah: "البقرة", updatedAt: 100 }],
      healthLogs: [{ id: "2026-11-26", day: "2026-11-26", weight: 5, sleepHours: 99, updatedAt: 100 }],
      transactions: [{ id: "8", kind: "gift", amount: -5, day: "2026-11-26", updatedAt: 100 }],
      moodLogs: [{ id: "2026-11-26", day: "2026-11-26", mood: 9, updatedAt: 100 }],
      calorieLogs: [{ id: "5", day: "2026-11-26", name: "أرز", calories: 0, updatedAt: 100 }],
    });
    assert.equal(clean.habitLogs.length, 1);
    assert.equal(clean.habitLogs[0].deleted, true);
    assert.equal(clean.habitLogs[0].done, undefined);
    assert.equal(clean.wirdLogs[0].pages, undefined);
    assert.equal(clean.healthLogs[0].weight, undefined);
    assert.equal(clean.healthLogs[0].sleepHours, undefined);
    assert.equal(clean.transactions[0].kind, undefined);
    assert.equal(clean.transactions[0].amount, undefined);
    assert.equal(clean.moodLogs[0].mood, undefined);
    assert.equal(clean.calorieLogs[0].calories, undefined);
  });

  await test("merge keeps life collections per-record last-write-wins", async () => {
    const existing = { revision: 1, updatedAt: 10, data: {
      wirdLogs: [{ id: "2026-11-25", day: "2026-11-25", pages: 2, surah: "البقرة", updatedAt: 100 }],
      moodLogs: [{ id: "2026-11-25", day: "2026-11-25", mood: 3, updatedAt: 100 }],
    } };
    const merged = real.mergeSync(existing, {
      wirdLogs: [{ id: "2026-11-25", day: "2026-11-25", pages: 4, surah: "البقرة", updatedAt: 200 }],
      moodLogs: [{ id: "2026-11-25", day: "2026-11-25", deleted: true, updatedAt: 300 }],
      habits: [{ id: "3", name: "قراءة", updatedAt: 150 }],
    }, 400);
    assert.equal(merged.revision, 2);
    assert.equal(merged.data.wirdLogs[0].pages, 4);
    assert.equal(merged.data.moodLogs[0].deleted, true);
    assert.equal(merged.data.habits[0].name, "قراءة");
    assert.equal(merged.data.tasks.length, 0);
    assert.equal(merged.data.calorieLogs.length, 0);
  });

  await test("payload de-duplicates by id and caps the record count", async () => {
    const payload = { tasks: Array.from({ length: real.MAX_RECORDS + 5 }, (_, i) => ({
      id: `t${i}`, title: `مهمة ${i}`, updatedAt: i + 1,
    })) };
    const clean = real.normalizeSyncPayload(payload);
    assert.equal(clean.tasks.length, real.MAX_RECORDS);
    assert.equal(clean.tasks[0].id, `t${real.MAX_RECORDS + 4}`);
  });

  await test("re-normalizing a stored snapshot keeps subject and type names", async () => {
    const first = real.normalizeSyncPayload({
      subjects: [{ id: "s1", title: "رياضيات", updatedAt: 100 }],
      taskTypes: [{ id: "t1", title: "واجب", updatedAt: 100 }],
    });
    const second = real.normalizeSyncPayload(first);
    assert.equal(second.subjects[0].name, "رياضيات");
    assert.equal(second.taskTypes[0].name, "واجب");
    const merged = real.mergeSync({ revision: 1, data: first }, {}, 200);
    assert.deepEqual(merged.data, second);
  });

  await test("merge uses the newer timestamp and keeps stored row on a tie", async () => {
    const existing = { revision: 2, updatedAt: 10, data: { tasks: [{ id: "1", title: "قديم", updatedAt: 100 }] } };
    const merged = real.mergeSync(existing, {
      tasks: [{ id: "1", title: "جديد", updatedAt: 200 }, { id: "2", title: "آخر", updatedAt: 150 }],
    }, 300);
    assert.equal(merged.revision, 3);
    assert.equal(merged.data.tasks.find((row) => row.id === "1").title, "جديد");
    assert.equal(merged.data.tasks.find((row) => row.id === "2").title, "آخر");
    const tie = real.mergeSync(existing, { tasks: [{ id: "1", title: "مساوي", updatedAt: 100 }] }, 300);
    assert.equal(tie.data.tasks[0].title, "قديم");
    const removed = real.mergeSync(existing, { tasks: [{ id: "1", deleted: true, updatedAt: 500 }] }, 600);
    assert.equal(removed.data.tasks[0].deleted, true);
  });

  await test("register refuses unknown profiles and weak passphrases", async () => {
    const handler = makeHandler(makeMockStore());
    assert.equal((await post(handler, { action: "register", profileId: UNKNOWN, passphrase: PASS_A })).statusCode, 403);
    assert.equal((await post(handler, { action: "register", profileId: PID_A, passphrase: "short" })).statusCode, 400);
    assert.equal((await post(handler, { action: "register", profileId: "", passphrase: PASS_A })).statusCode, 400);
  });

  await test("register requires the current passphrase before changing it", async () => {
    const store = makeMockStore();
    const handler = makeHandler(store);
    assert.equal((await post(handler, { action: "register", profileId: PID_A, passphrase: PASS_A, uid: "uid-a" })).statusCode, 200);
    assert.equal(store._accounts.get(PID_A), PASS_A);
    assert.equal((await post(handler, { action: "register", profileId: PID_A, passphrase: PASS_B, uid: "uid-a" })).statusCode, 401);
    assert.equal((await post(handler, {
      action: "register", profileId: PID_A, passphrase: PASS_B, currentPassphrase: PASS_A, uid: "uid-a",
    })).statusCode, 200);
    assert.equal(store._accounts.get(PID_A), PASS_B);
  });

  await test("push and pull require credentials and keep accounts isolated", async () => {
    const store = makeMockStore();
    const handler = makeHandler(store);
    await post(handler, { action: "register", profileId: PID_A, passphrase: PASS_A, uid: "uid-a" });
    await post(handler, { action: "register", profileId: PID_B, passphrase: PASS_B, uid: "uid-b" });

    const bad = await post(handler, {
      action: "push", profileId: PID_A, passphrase: "wrong passphrase", data: { tasks: [] },
    });
    assert.equal(bad.statusCode, 401);
    assert.equal(bad.body.error, "invalid_credentials");

    const pushed = await post(handler, {
      action: "push", profileId: PID_A, passphrase: PASS_A,
      data: { tasks: [{ id: "1", title: "مهمة", notes: "سر", updatedAt: 1000 }] },
    });
    assert.equal(pushed.statusCode, 200);
    assert.equal(pushed.body.data.tasks[0].notes, undefined);
    assert.equal(pushed.body.revision, 1);

    const mine = await post(handler, { action: "pull", profileId: PID_A, passphrase: PASS_A });
    assert.equal(mine.body.data.tasks.length, 1);
    assert.deepEqual(Object.keys(mine.body.data).sort(), [
      "calorieLogs", "habitLogs", "habits", "healthLogs", "moodLogs",
      "subjects", "taskTypes", "tasks", "transactions", "wirdLogs",
    ]);
    const other = await post(handler, { action: "pull", profileId: PID_B, passphrase: PASS_B });
    assert.equal(other.body.data.tasks.length, 0);
    assert.equal(other.body.revision, 0);
    assert.equal((await post(handler, { action: "pull", profileId: PID_B, passphrase: PASS_A })).statusCode, 401);
  });

  await test("rate limiter uses a fixed window and clears on success", async () => {
    const start = 1_000_000;
    let state;
    for (let i = 0; i < realRate.LIMITS.register; i += 1) {
      const gate = realRate.evaluate(state, "register", start);
      assert.equal(gate.allowed, true);
      state = realRate.after(gate.state, "register", start, false).state;
    }
    const blocked = realRate.evaluate(state, "register", start + 1_000);
    assert.equal(blocked.allowed, false);
    assert.ok(blocked.retryAfter > 0);
    const expired = realRate.evaluate(state, "register", start + realRate.WINDOW_MS + 1);
    assert.equal(expired.allowed, true);
    assert.equal(realRate.after(expired.state, "register", start + realRate.WINDOW_MS + 1, true).state.count, 0);
    // Unknown actions share the conservative default limit and never divide by zero.
    assert.equal(realRate.evaluate(undefined, "unknown", start).allowed, true);
  });

  await test("repeated failed auth is throttled per caller without blocking another account", async () => {
    const handler = makeHandler(makeMockStore());
    await post(handler, { action: "register", profileId: PID_A, passphrase: PASS_A, uid: "uid-a" });
    for (let i = 0; i < realRate.LIMITS.pull; i += 1) {
      const res = await post(handler, { action: "pull", profileId: PID_A, passphrase: "wrong passphrase" });
      assert.equal(res.statusCode, 401);
    }
    const throttled = await post(handler, { action: "pull", profileId: PID_A, passphrase: PASS_A });
    assert.equal(throttled.statusCode, 429);
    assert.equal(throttled.body.error, "too_many_requests");
    assert.ok(Number(throttled.headers["Retry-After"]) > 0);
    assert.equal(throttled.headers["Cache-Control"], "no-store");
    const other = await post(handler, { action: "pull", profileId: PID_B, passphrase: "wrong passphrase" });
    assert.equal(other.statusCode, 401);
    assert.equal((await post(handler, { action: "unknown", profileId: PID_A })).statusCode, 400);
  });

  await test("register throttling is keyed by caller, not by the guessed profile", async () => {
    const handler = makeHandler(makeMockStore());
    const headers = { "x-forwarded-for": "203.0.113.7" };
    for (let i = 0; i < realRate.LIMITS.register; i += 1) {
      assert.equal((await post(handler, {
        action: "register", profileId: UNKNOWN, passphrase: PASS_A,
      }, headers)).statusCode, 403);
    }
    const throttled = await post(handler, {
      action: "register", profileId: PID_A, passphrase: PASS_A,
    }, headers);
    assert.equal(throttled.statusCode, 429);
    const other = await post(handler, {
      action: "register", profileId: PID_A, passphrase: PASS_A, uid: "uid-a",
    }, { "x-forwarded-for": "203.0.113.8" });
    assert.equal(other.statusCode, 200);
  });

  await test("only POST is accepted and every answer is no-store", async () => {
    const handler = makeHandler(makeMockStore());
    const res = makeRes();
    await handler({ method: "GET" }, res);
    assert.equal(res.statusCode, 405);
    const posted = await post(handler, { action: "pull", profileId: PID_A, passphrase: PASS_A });
    assert.equal(posted.headers["Cache-Control"], "no-store");
  });

  // ---- H1: end-to-end encryption (v2 envelopes) ----
  // A real AES-256-GCM envelope produced by Node crypto for the same key
  // derivation the Android/desktop clients use (see SyncCryptoTest).
  const V2_ENC = "AQIDBAUGBwgJCgsMvq1lqXvIeq45UsatXGawI04oXnKqMKRRcCvsUmKhWcJEGYJUy8Nc4iZqKGf3k5x4mTzAMppSrN18EQqCnXB3a6Xc8jTE28cd/zoO3Pcm6vwFfXJ0whGrl7+gpKJFV6Diq1nAiQ==";

  await test("v2 envelope passes cleanRecord opaquely and drops smuggled plaintext", async () => {
    const clean = real.normalizeSyncPayload({
      tasks: [{
        id: "42", updatedAt: 1000, v: 2, enc: V2_ENC,
        title: "must not be stored", notes: "secret note",
      }],
    });
    assert.equal(clean.tasks.length, 1);
    assert.deepEqual(Object.keys(clean.tasks[0]).sort(), ["enc", "id", "updatedAt", "v"]);
    assert.equal(clean.tasks[0].enc, V2_ENC);
    assert.equal(clean.tasks[0].title, undefined);
  });

  await test("v2 envelope with bad enc is dropped", async () => {
    const clean = real.normalizeSyncPayload({
      tasks: [
        { id: "a", updatedAt: 1000, v: 2, enc: "short" },
        { id: "b", updatedAt: 1000, v: 2, enc: "!!!not-base64!!!" },
        { id: "c", updatedAt: 1000, v: 2, enc: "x".repeat(9000) },
        { id: "d", updatedAt: 1000, v: 2 },
      ],
    });
    assert.equal(clean.tasks.length, 0);
  });

  await test("mergeSync keeps v2 records and LWW still decides by updatedAt", async () => {
    const stored = { revision: 1, updatedAt: 100, data: real.normalizeSyncPayload({
      tasks: [{ id: "1", updatedAt: 100, title: "old plaintext" }],
    }) };
    const merged = real.mergeSync(stored, {
      tasks: [{ id: "1", updatedAt: 200, v: 2, enc: V2_ENC }],
    }, 300);
    assert.equal(merged.data.tasks.length, 1);
    assert.equal(merged.data.tasks[0].v, 2);
    assert.equal(merged.data.tasks[0].enc, V2_ENC);
    // An older v2 record does not beat a newer plaintext one.
    const merged2 = real.mergeSync(merged, {
      tasks: [{ id: "1", updatedAt: 150, title: "stale plaintext" }],
    }, 400);
    assert.equal(merged2.data.tasks[0].v, 2);
  });

  await test("v2 envelope replaces v1 plaintext at an equal timestamp (migration)", async () => {
    const stored = { revision: 1, updatedAt: 100, data: real.normalizeSyncPayload({
      tasks: [{ id: "1", updatedAt: 100, title: "old plaintext" }],
    }) };
    const merged = real.mergeSync(stored, {
      tasks: [{ id: "1", updatedAt: 100, v: 2, enc: V2_ENC }],
    }, 300);
    assert.equal(merged.data.tasks.length, 1);
    assert.equal(merged.data.tasks[0].v, 2);
    // The reverse never happens: v1 at an equal timestamp does not downgrade v2.
    const merged2 = real.mergeSync(merged, {
      tasks: [{ id: "1", updatedAt: 100, title: "downgrade attempt" }],
    }, 400);
    assert.equal(merged2.data.tasks[0].v, 2);
  });

  await test("stripEncrypted removes v2 but keeps v1 and the revision", async () => {
    const snap = {
      revision: 5, updatedAt: 999,
      data: real.normalizeSyncPayload({
        tasks: [
          { id: "1", updatedAt: 100, v: 2, enc: V2_ENC },
          { id: "2", updatedAt: 100, title: "plain" },
        ],
      }),
    };
    const stripped = real.stripEncrypted(snap);
    assert.equal(stripped.revision, 5);
    assert.equal(stripped.data.tasks.length, 1);
    assert.equal(stripped.data.tasks[0].id, "2");
  });

  await test("API: v2 client sees envelopes, v1 client does not (pull and push)", async () => {
    const store = makeMockStore();
    const handler = makeHandler(store);
    const reg = await post(handler, { action: "register", profileId: PID_A, passphrase: PASS_A, uid: "uid-a" });
    assert.equal(reg.statusCode, 200);
    const pushed = await post(handler, {
      action: "push", profileId: PID_A, passphrase: PASS_A, syncVersion: 2,
      data: { tasks: [{ id: "42", updatedAt: 1000, v: 2, enc: V2_ENC }] },
    });
    assert.equal(pushed.statusCode, 200);
    assert.equal(pushed.body.data.tasks.length, 1);
    assert.equal(pushed.body.data.tasks[0].enc, V2_ENC);
    // v2 pull returns the envelope.
    const pulled2 = await post(handler, {
      action: "pull", profileId: PID_A, passphrase: PASS_A, syncVersion: 2,
    });
    assert.equal(pulled2.statusCode, 200);
    assert.equal(pulled2.body.data.tasks.length, 1);
    assert.equal(pulled2.body.data.tasks[0].v, 2);
    // v1 pull (no syncVersion) strips it - the record must not come back as plaintext.
    const pulled1 = await post(handler, {
      action: "pull", profileId: PID_A, passphrase: PASS_A,
    });
    assert.equal(pulled1.statusCode, 200);
    assert.equal(pulled1.body.data.tasks.length, 0);
    assert.equal(pulled1.body.revision, pulled2.body.revision);
    // v1 push response also strips the v2 record.
    const pushed1 = await post(handler, {
      action: "push", profileId: PID_A, passphrase: PASS_A,
      data: { tasks: [{ id: "9", updatedAt: 1001, title: "plain" }] },
    });
    assert.equal(pushed1.statusCode, 200);
    assert.equal(pushed1.body.data.tasks.length, 1);
    assert.equal(pushed1.body.data.tasks[0].id, "9");
  });

  // ---- M1 (H2): first sync registration requires uid ownership proof ----
  await test("first sync registration requires the owner install id (M1)", async () => {
    const store = makeMockStore();
    const handler = makeHandler(store);
    // No uid at all: forbidden.
    const noUid = await post(handler, {
      action: "register", profileId: PID_A, passphrase: PASS_A,
    });
    assert.equal(noUid.statusCode, 403);
    assert.equal(noUid.body.error, "profile_not_owner");
    // Wrong uid: forbidden.
    const wrongUid = await post(handler, {
      action: "register", profileId: PID_A, passphrase: PASS_A, uid: "uid-b",
    });
    assert.equal(wrongUid.statusCode, 403);
    assert.equal(wrongUid.body.error, "profile_not_owner");
    // The owner uid: allowed, and the account is now created.
    const ok = await post(handler, {
      action: "register", profileId: PID_A, passphrase: PASS_A, uid: "uid-a",
    });
    assert.equal(ok.statusCode, 200);
    // A second device with only the profile id cannot take it over afterwards:
    // the account exists, so only the current passphrase works.
    const takeover = await post(handler, {
      action: "register", profileId: PID_A, passphrase: PASS_B, uid: "uid-b",
    });
    assert.equal(takeover.statusCode, 401);
  });

  // ---- P-02: sync account deletion ----
  await test("sync delete removes the snapshot and the account (P-02)", async () => {
    const store = makeMockStore();
    const handler = makeHandler(store);
    await post(handler, {
      action: "register", profileId: PID_A, passphrase: PASS_A, uid: "uid-a",
    });
    const pushed = await post(handler, {
      action: "push", profileId: PID_A, passphrase: PASS_A,
      data: { tasks: [{ id: "1", updatedAt: 1000, title: "مهمة" }] },
    });
    assert.equal(pushed.statusCode, 200);
    // Wrong passphrase: nothing is deleted.
    const wrong = await post(handler, {
      action: "delete", profileId: PID_A, passphrase: "wrong passphrase",
    });
    assert.equal(wrong.statusCode, 401);
    const stillThere = await post(handler, {
      action: "pull", profileId: PID_A, passphrase: PASS_A,
    });
    assert.equal(stillThere.statusCode, 200);
    // Right passphrase: snapshot + account are gone.
    const deleted = await post(handler, {
      action: "delete", profileId: PID_A, passphrase: PASS_A,
    });
    assert.equal(deleted.statusCode, 200);
    assert.equal(deleted.body.ok, true);
    const gone = await post(handler, {
      action: "pull", profileId: PID_A, passphrase: PASS_A,
    });
    assert.equal(gone.statusCode, 401);
    // Re-registration after deletion works (fresh account, same owner uid).
    const again = await post(handler, {
      action: "register", profileId: PID_A, passphrase: PASS_A, uid: "uid-a",
    });
    assert.equal(again.statusCode, 200);
  });

  console.log(`sync tests: ${passed} passed`);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
