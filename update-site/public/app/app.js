/* Web app wiring: local-first state plus explicit, opt-in sync. No notes and no stored secret. */
(function () {
  "use strict";

  const D = window.MonazemWebData;
  const STORAGE_KEY = "monazem-web-v1";
  const THEME_KEY = "monazem-theme";
  const $ = (id) => document.getElementById(id);

  const sections = [
    { kind: "tasks", list: "tasks-list", count: "tasks-count", form: "task-form" },
    { kind: "subjects", list: "subjects-list", count: "subjects-count", form: "subjects-form" },
    { kind: "taskTypes", list: "types-list", count: "types-count", form: "types-form" },
  ];

  let state = load();

  function load() {
    try {
      return D.normalizeState(JSON.parse(localStorage.getItem(STORAGE_KEY) || "{}"));
    } catch {
      return D.emptyState();
    }
  }

  function save() {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  }

  function say(id, message, kind) {
    const node = $(id);
    node.textContent = message;
    if (kind) node.setAttribute("data-kind", kind);
    else node.removeAttribute("data-kind");
  }

  function label(row) {
    return row.title || row.name || "(بلا اسم)";
  }

  function render() {
    for (const section of sections) {
      const rows = D.visibleRows(state, section.kind);
      const list = $(section.list);
      list.replaceChildren();
      if (!rows.length) {
        const empty = document.createElement("li");
        empty.className = "empty";
        empty.textContent = "لا توجد سجلات بعد.";
        list.append(empty);
      }
      for (const row of rows) {
        const item = document.createElement("li");
        const text = document.createElement("span");
        text.className = "title";
        text.textContent = label(row);
        const meta = document.createElement("span");
        meta.className = "meta";
        if (section.kind === "tasks" && row.minutes) meta.textContent = `${row.minutes} دقيقة`;
        const remove = document.createElement("button");
        remove.className = "ghost";
        remove.type = "button";
        remove.textContent = "حذف";
        remove.addEventListener("click", () => {
          state = D.remove(state, section.kind, row.id, Date.now());
          save();
          render();
        });
        const side = document.createElement("span");
        side.append(meta, remove);
        item.append(text, side);
        list.append(item);
      }
      $(section.count).textContent = `${rows.length} سجل`;
    }
  }

  for (const section of sections) {
    $(section.form).addEventListener("submit", (event) => {
      event.preventDefault();
      const form = event.currentTarget;
      const values = Object.fromEntries(new FormData(form));
      state = D.put(state, section.kind, values, Date.now());
      save();
      form.reset();
      render();
    });
  }

  function credentials() {
    return {
      profileId: $("profile-id").value.trim(),
      passphrase: $("passphrase").value,
    };
  }

  async function callSync(action, withData) {
    const ids = credentials();
    if (!ids.profileId || !ids.passphrase) {
      say("account-status", "أدخل معرّف الحساب وكلمة المرور أولًا.", "error");
      return null;
    }
    const payload = { action, ...ids };
    if (withData) payload.data = state;
    const response = await fetch("/api/sync", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      cache: "no-store",
      body: JSON.stringify(payload),
    });
    const body = await response.json().catch(() => ({}));
    if (!response.ok) {
      say("account-status", `تعذّر الطلب (${body.error || response.status}).`, "error");
      return null;
    }
    return body;
  }

  $("register").addEventListener("click", async () => {
    try {
      const result = await callSync("register", false);
      if (result) say("account-status", "تم تفعيل المزامنة لهذا الحساب.");
    } catch {
      say("account-status", "تعذّر الاتصال بخادم المزامنة.", "error");
    }
  });

  $("pull").addEventListener("click", async () => {
    try {
      const result = await callSync("pull", false);
      if (!result) return;
      state = D.mergeRemote(state, result.data, Date.now());
      save();
      render();
      say("account-status", "تم سحب أحدث البيانات.");
    } catch {
      say("account-status", "تعذّر الاتصال بخادم المزامنة.", "error");
    }
  });

  $("push").addEventListener("click", async () => {
    try {
      const result = await callSync("push", true);
      if (!result) return;
      state = D.mergeRemote(state, result.data, Date.now());
      save();
      render();
      say("account-status", "تم دفع البيانات.");
    } catch {
      say("account-status", "تعذّر الاتصال بخادم المزامنة.", "error");
    }
  });

  const root = document.documentElement;
  if (localStorage.getItem(THEME_KEY) === "light") root.setAttribute("data-theme", "light");
  $("theme-toggle").addEventListener("click", () => {
    const light = root.getAttribute("data-theme") === "light";
    if (light) root.removeAttribute("data-theme");
    else root.setAttribute("data-theme", "light");
    localStorage.setItem(THEME_KEY, light ? "dark" : "light");
  });

  render();
})();
