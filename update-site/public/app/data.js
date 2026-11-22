/**
 * Local-first data helpers for the web app. No notes, device ids or push tokens are ever part of
 * this model. The same file runs in the browser and under Node for the local regression tests.
 */
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  if (root) root.MonazemWebData = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  const COLLECTIONS = ["tasks", "subjects", "taskTypes"];
  const MAX_RECORDS = 2000;
  const TITLE_LIMIT = 200;
  const TASK_CATEGORIES = new Set(["study", "university", "personal", "work", "habits", "other"]);
  const TASK_STATUSES = new Set(["todo", "doing", "done", "postponed", "cancelled"]);
  const RECURRENCES = new Set(["none", "daily", "weekly", "monthly"]);

  const cut = (value, max) => String(value ?? "").trim().slice(0, max);

  function time(value) {
    const number = Number(value);
    return Number.isSafeInteger(number) && number > 0 ? number : 0;
  }

  function emptyState() {
    return { tasks: [], subjects: [], taskTypes: [] };
  }

  function labelField(kind) {
    return kind === "tasks" ? "title" : "name";
  }

  /** The wire payload is a strict whitelist; anything else is dropped before it leaves the device. */
  function cleanRecord(item, kind) {
    if (!item || typeof item !== "object" || Array.isArray(item)) return null;
    const id = cut(item.id, 100);
    if (!id) return null;
    const record = { id, updatedAt: time(item.updatedAt) };
    const label = cut(item[labelField(kind)], TITLE_LIMIT);
    if (label) record[labelField(kind)] = label;
    if (kind === "tasks") {
      const minutes = Number(item.minutes);
      if (Number.isInteger(minutes) && minutes >= 1 && minutes <= 720) record.minutes = minutes;
      const category = cut(item.category, 24).toLowerCase();
      if (TASK_CATEGORIES.has(category)) record.category = category;
      const priority = Number(item.priority);
      if (Number.isInteger(priority) && priority >= 0 && priority <= 3) record.priority = priority;
      const status = cut(item.status, 24).toLowerCase();
      if (TASK_STATUSES.has(status)) record.status = status;
      const dueAt = Number(item.dueAt);
      if (Number.isSafeInteger(dueAt) && dueAt >= 0) record.dueAt = dueAt;
      const recurrence = cut(item.recurrence, 24).toLowerCase();
      if (RECURRENCES.has(recurrence)) record.recurrence = recurrence;
    }
    if (kind === "taskTypes" && item.archived === true) record.archived = true;
    if (item.deleted === true) record.deleted = true;
    return record;
  }

  function normalizeCollection(items, kind) {
    const byId = new Map();
    for (const item of (Array.isArray(items) ? items : []).slice(0, MAX_RECORDS * 2)) {
      const record = cleanRecord(item, kind);
      if (!record) continue;
      const previous = byId.get(record.id);
      if (!previous || record.updatedAt >= previous.updatedAt) byId.set(record.id, record);
    }
    return [...byId.values()]
      .sort((a, b) => b.updatedAt - a.updatedAt || a.id.localeCompare(b.id))
      .slice(0, MAX_RECORDS);
  }

  function normalizeState(state) {
    const source = state && typeof state === "object" ? state : {};
    return {
      tasks: normalizeCollection(source.tasks, "tasks"),
      subjects: normalizeCollection(source.subjects, "subjects"),
      taskTypes: normalizeCollection(source.taskTypes, "taskTypes"),
    };
  }

  /**
   * On pull the server snapshot is the stored row, so an equal timestamp keeps the server value.
   * Never called with credentials or profile ids: those stay in the account form only.
   */
  function mergeRemote(local, remote, now) {
    const merged = normalizeState(local);
    const incoming = normalizeState(remote);
    for (const kind of COLLECTIONS) {
      const byId = new Map(merged[kind].map((row) => [row.id, row]));
      for (const row of incoming[kind]) {
        const current = byId.get(row.id);
        if (!current || row.updatedAt > current.updatedAt) byId.set(row.id, row);
      }
      merged[kind] = normalizeCollection([...byId.values()], kind);
    }
    return merged;
  }

  function put(state, kind, values, now) {
    const next = normalizeState(state);
    const id = cut(values.id, 100) || newId();
    const stamp = time(now) || Date.now();
    const row = cleanRecord({ ...values, id, updatedAt: stamp }, kind);
    const list = next[kind].filter((item) => item.id !== id);
    if (row) list.push(row);
    next[kind] = normalizeCollection(list, kind);
    return next;
  }

  function remove(state, kind, id, now) {
    const next = normalizeState(state);
    const cleanId = cut(id, 100);
    if (!cleanId) return next;
    const stamp = time(now) || Date.now();
    const list = next[kind].filter((item) => item.id !== cleanId);
    list.push({ id: cleanId, updatedAt: stamp, deleted: true });
    next[kind] = normalizeCollection(list, kind);
    return next;
  }

  function visibleRows(state, kind) {
    return normalizeState(state)[kind].filter((row) => row.deleted !== true);
  }

  function newId() {
    if (typeof crypto !== "undefined" && crypto.randomUUID) return crypto.randomUUID();
    return `w-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
  }

  return {
    COLLECTIONS, MAX_RECORDS, emptyState, normalizeState, mergeRemote,
    put, remove, visibleRows, newId,
  };
});
