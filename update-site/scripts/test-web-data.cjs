/**
 * Local regression test for the web app data model. No browser, network or token is involved:
 *   npm run test:web
 */
const assert = require("node:assert/strict");
const data = require("../public/app/data.js");

let passed = 0;
function test(name, fn) {
  fn();
  passed += 1;
  console.log(`ok - ${name}`);
}

const NOW = 1_700_000_000_000;

test("a fresh state has only the three allowed collections", () => {
  assert.deepEqual(Object.keys(data.emptyState()).sort(), ["subjects", "taskTypes", "tasks"]);
});

test("normalizing drops notes, device ids, push tokens and unknown collections", () => {
  const state = data.normalizeState({
    tasks: [{
      id: "t1", title: "مهمة", notes: "ملاحظة خاصة", minutes: 30, category: "study",
      status: "todo", updatedAt: NOW, deviceId: "d1", pushToken: "p1", uid: "u1",
    }],
    subjects: [{ id: "s1", name: "رياضيات", notes: "سر", updatedAt: NOW }],
    taskTypes: [{ id: "y1", name: "واجب", updatedAt: NOW, archived: true }],
    notes: [{ id: "n1", text: "لا تخرج" }],
    messages: [{ id: "m1" }],
  });
  assert.equal(state.tasks[0].title, "مهمة");
  assert.equal(state.tasks[0].notes, undefined);
  assert.equal(state.tasks[0].deviceId, undefined);
  assert.equal(state.tasks[0].pushToken, undefined);
  assert.equal(state.subjects[0].name, "رياضيات");
  assert.equal(state.subjects[0].notes, undefined);
  assert.equal(state.taskTypes[0].archived, true);
  assert.equal(state.notes, undefined);
  assert.equal(state.messages, undefined);
});

test("put and remove stamp a record and keep a tombstone out of visible rows", () => {
  let state = data.emptyState();
  state = data.put(state, "tasks", { title: "مهمة", minutes: 25, category: "study" }, NOW);
  assert.equal(state.tasks.length, 1);
  assert.equal(state.tasks[0].updatedAt, NOW);
  state = data.remove(state, "tasks", state.tasks[0].id, NOW + 1);
  assert.equal(data.visibleRows(state, "tasks").length, 0);
  assert.equal(state.tasks[0].deleted, true);
});

test("pull merge keeps the newer row and the server row on an equal timestamp", () => {
  const local = data.put(data.emptyState(), "tasks", { id: "t1", title: "محلي", updatedAt: NOW }, NOW);
  const newer = data.mergeRemote(local, { tasks: [{ id: "t1", title: "خادم", updatedAt: NOW + 1 }] }, NOW + 2);
  assert.equal(newer.tasks[0].title, "خادم");
  const older = data.mergeRemote(local, { tasks: [{ id: "t1", title: "قديم", updatedAt: NOW - 1 }] }, NOW + 2);
  assert.equal(older.tasks[0].title, "محلي");
  const tie = data.mergeRemote(local, { tasks: [{ id: "t1", title: "مساوٍ", updatedAt: NOW }] }, NOW + 2);
  assert.equal(tie.tasks[0].title, "محلي");
});

test("a newer remote tombstone hides a local row", () => {
  const local = data.put(data.emptyState(), "subjects", { id: "s1", name: "فيزياء", updatedAt: NOW }, NOW);
  const merged = data.mergeRemote(local, { subjects: [{ id: "s1", deleted: true, updatedAt: NOW + 1 }] }, NOW + 2);
  assert.equal(data.visibleRows(merged, "subjects").length, 0);
});

test("the model never carries an account key or a passphrase", () => {
  const state = data.put(data.emptyState(), "tasks", {
    title: "مهمة", profileId: "secret-id", passphrase: "secret-pass", updatedAt: NOW,
  }, NOW);
  assert.equal(JSON.stringify(state).includes("secret-id"), false);
  assert.equal(JSON.stringify(state).includes("secret-pass"), false);
});

console.log(`web data tests: ${passed} passed`);
