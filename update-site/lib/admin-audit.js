/**
 * Admin-action audit trail (B4 / SA-P2-1).
 *
 * Private Blob `focus-study/admin-audit.json`, last 500 events, newest first.
 * Events carry an actor, an action and a short detail — never a password, a
 * token, a passphrase or a client address. Writes are best-effort: an audit
 * failure must never fail the admin action it records.
 *
 * Local fallback (no Blob token, i.e. local dev / tests): update-site/.data/,
 * the same fallback the messaging store uses. No new secret or service.
 */
const fs = require("node:fs");
const path = require("node:path");
const { storageToken } = require("./blob-access");

const AUDIT_PATH = "focus-study/admin-audit.json";
const ACCESS = "private";
const LOCAL_DIR = path.join(__dirname, "..", ".data");
const MAX_EVENTS = 500;

async function readAdminAudit() {
  const token = storageToken(ACCESS);
  if (token) {
    try {
      const { get } = await import("@vercel/blob");
      const result = await get(AUDIT_PATH, { access: ACCESS, token });
      if (result?.statusCode === 200 && result.stream) {
        const record = JSON.parse(await new Response(result.stream).text());
        return Array.isArray(record.events) ? record.events : [];
      }
    } catch (error) {
      console.error("admin audit read failed", error?.message || "unknown");
      return [];
    }
  }
  try {
    const record = JSON.parse(fs.readFileSync(path.join(LOCAL_DIR, path.basename(AUDIT_PATH)), "utf8"));
    return Array.isArray(record.events) ? record.events : [];
  } catch {
    return [];
  }
}

async function writeAdminAudit(events) {
  const token = storageToken(ACCESS);
  const value = JSON.stringify({ events });
  if (token) {
    const { put } = await import("@vercel/blob");
    await put(AUDIT_PATH, value, {
      access: ACCESS, token, addRandomSuffix: false, allowOverwrite: true,
      contentType: "application/json; charset=utf-8", cacheControlMaxAge: 0,
    });
    return;
  }
  fs.mkdirSync(LOCAL_DIR, { recursive: true });
  fs.writeFileSync(path.join(LOCAL_DIR, path.basename(AUDIT_PATH)), value, "utf8");
}

/** Best-effort: never throws into the admin action being audited. */
async function appendAdminAudit(events) {
  try {
    const existing = await readAdminAudit();
    const added = (Array.isArray(events) ? events : []).map((event) => ({
      id: `a-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      at: new Date().toISOString(),
      actor: String(event.actor || "admin").slice(0, 64),
      action: String(event.action || "").slice(0, 64),
      detail: String(event.detail || "").slice(0, 200),
      outcome: String(event.outcome || "ok").slice(0, 32),
    }));
    if (!added.length) return existing;
    const next = [...added, ...existing].slice(0, MAX_EVENTS);
    await writeAdminAudit(next);
    return next;
  } catch (error) {
    console.error("admin audit write failed", error?.message || "unknown");
    return [];
  }
}

module.exports = { AUDIT_PATH, MAX_EVENTS, readAdminAudit, appendAdminAudit };
