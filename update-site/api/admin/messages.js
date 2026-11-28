const { isAdmin, requireAdmin, originIsSame, parseBody, json } = require("../../lib/admin-auth");
const {
  readMessages, appendMessage, removeMessage, readInbox, markSupport, readUsers,
  readNotificationSounds, writeNotificationSounds, normalizeProfileId,
  readDeliveryEvents, appendDeliveryEvents, readRegistrationEvents, appendRegistrationEvents,
  deleteUser, normalizeExpiresAt, expiresAtFromDays,
} = require("../../lib/messaging-store");
const { readAdminAudit, appendAdminAudit } = require("../../lib/admin-audit");
const { deleteSync, deleteAccount } = require("../../lib/sync-store");
const { parseSupportImagePath, sniffImageMime, readPrivateBlobBytes } = require("../../lib/support-images");
const { normalizeUserCode, userCode } = require("../../lib/user-code");

const ALLOWED_AUDIENCES = new Set(["", "student", "graduate", "professional", "retired", "general"]);
const TARGET_MODES = new Set(["broadcast", "audience", "person"]);

/** Admin: publish with an explicit target mode, inspect the audit ledger, or delete a message. */
module.exports = async function adminMessages(req, res) {
  if (!isAdmin(req)) return json(res, 401, { error: "authentication_required" });
  try {
    // Inbox, users, sounds and the delivery ledger live on this one function: the Hobby plan allows
    // 12 serverless functions per deployment.
    const view = String(req.query?.view || "");
    if (req.method === "GET") {
      // Reads allow a mustChange session (the admin still needs to see the
      // panel to change the password); the session generation is still checked.
      const gate = await requireAdmin(req, { allowMustChange: true });
      if (!gate.ok) return json(res, gate.status, { error: gate.error });
      if (view === "inbox") {
        const items = await readInbox();
        return json(res, 200, { items, open: items.filter((item) => item.status === "new").length });
      }
      if (view === "users") {
        const users = (await readUsers()).sort((a, b) =>
          String(b.firstSeen || "").localeCompare(String(a.firstSeen || ""))
        );
        return json(res, 200, { users, total: users.length });
      }
      if (view === "sounds") return json(res, 200, await readNotificationSounds());
      if (view === "deliveries") {
        const events = await readDeliveryEvents();
        return json(res, 200, { events, total: events.length });
      }
      // B4: admin-action audit trail + registration log read views (SA-P2-1).
      if (view === "audit") {
        const events = await readAdminAudit();
        return json(res, 200, { events, total: events.length });
      }
      if (view === "registrations") {
        const events = await readRegistrationEvents();
        return json(res, 200, { events, total: events.length });
      }
      // B8: admin-only proxied read of a private support image. The MIME type
      // is sniffed from the bytes, never trusted from storage; no-store + nosniff.
      if (view === "support-image") return supportImage(req, res);
      return json(res, 200, { messages: await readMessages() });
    }
    if (req.method === "DELETE") {
      if (!originIsSame(req)) return json(res, 403, { error: "origin_rejected" });
      const gate = await requireAdmin(req);
      if (!gate.ok) return json(res, gate.status, { error: gate.error });
      const id = String(parseBody(req.body).id || "").trim();
      if (!id) return json(res, 400, { error: "id_required" });
      const messages = await removeMessage(id);
      await appendAdminAudit([{ actor: process.env.ADMIN_USERNAME || "admin", action: "message_delete", outcome: "ok", detail: `message ${id}` }]);
      return json(res, 200, { messages });
    }
    if (req.method !== "POST") return json(res, 405, { error: "method_not_allowed" });
    if (!originIsSame(req)) return json(res, 403, { error: "origin_rejected" });
    // B2/B6: mutations require a current-generation session and reject
    // mustChange sessions server-side (SA-P1-3), plus the origin check (SA-P2-4).
    const gate = await requireAdmin(req);
    if (!gate.ok) return json(res, gate.status, { error: gate.error });
    const body = parseBody(req.body);
    // B5: admin user deletion (SA-P2-2). Single or bulk; bulk needs its own
    // explicit confirmation string and is capped at 50 users per call.
    if (body.action === "deleteUser" || body.action === "deleteUsers") {
      return deleteUsers(req, res, body);
    }
    if (body.markId) {
      const id = String(body.markId || "").trim();
      const status = ["new", "handled"].includes(body.status) ? body.status : "handled";
      const items = await markSupport(id, status);
      await appendAdminAudit([{ actor: process.env.ADMIN_USERNAME || "admin", action: "support_mark", outcome: "ok", detail: `${id} -> ${status}` }]);
      return json(res, 200, { items });
    }
    if (view === "sounds") {
      const sounds = await writeNotificationSounds(body.sounds);
      await appendAdminAudit([{ actor: process.env.ADMIN_USERNAME || "admin", action: "sounds_change", outcome: "ok", detail: "notification sounds updated" }]);
      return json(res, 200, sounds);
    }

    const title = String(body.title || "").trim().slice(0, 90);
    const text = String(body.body || "").trim().slice(0, 900);
    const requestedMode = String(body.targetMode || "").trim();
    const audienceProfile = ALLOWED_AUDIENCES.has(String(body.audienceProfile || ""))
      ? String(body.audienceProfile || "") : "";
    const minVersionCode = Math.max(0, Math.min(1_000_000, Number(body.minVersionCode || 0) || 0));
    const rawRef = String(body.targetRef || body.targetUserId || "").trim();
    const hasAudienceFilter = Boolean(audienceProfile || minVersionCode > 0);

    // New clients send targetMode explicitly. Older clients are inferred from the fields they send,
    // but an explicit broadcast with filters is rejected instead of silently changing audience.
    const targetMode = requestedMode || (
      rawRef ? "person" : hasAudienceFilter ? "audience" : "broadcast"
    );
    if (!TARGET_MODES.has(targetMode)) return json(res, 400, { error: "invalid_target_mode" });
    if (requestedMode === "broadcast" && (rawRef || hasAudienceFilter)) {
      return json(res, 400, { error: "mode_field_conflict" });
    }
    if (targetMode === "audience" && !hasAudienceFilter) {
      return json(res, 400, { error: "audience_required" });
    }
    if (targetMode === "person" && !rawRef) {
      return json(res, 400, { error: "target_required" });
    }

    // The code is public, so resolve it here to the server-issued profile id. The install id is kept
    // alongside it only so 0.29.0 builds still receive their messages; the code is a dashboard label.
    let targetProfileId = "";
    let targetUserId = "";
    let targetCode = "";
    if (targetMode === "person") {
      const people = await readUsers();
      const pastedCode = normalizeUserCode(rawRef);
      const pastedProfileId = normalizeProfileId(rawRef);
      let found;
      if (pastedCode) {
        found = people.find((person) =>
          String(person.code || userCode(String(person.id || ""))).toUpperCase() === pastedCode
        );
        if (!found) return json(res, 404, { error: "target_user_not_found" });
        targetCode = pastedCode;
      } else if (pastedProfileId) {
        found = people.find((person) =>
          normalizeProfileId(person.profileId || person.userId) === pastedProfileId
        );
        if (!found) return json(res, 404, { error: "target_user_not_found" });
        targetProfileId = pastedProfileId;
      } else {
        const cleanId = rawRef.toLowerCase();
        if (!/^[a-z0-9-]{6,64}$/.test(cleanId)) return json(res, 400, { error: "invalid_target_user" });
        found = people.find((person) => String(person.id || "").toLowerCase() === cleanId);
        if (!found) return json(res, 404, { error: "target_user_not_found" });
      }
      targetProfileId = normalizeProfileId(found.profileId || found.userId) || targetProfileId;
      if (!targetProfileId) return json(res, 409, { error: "target_profile_not_ready" });
      targetUserId = String(found.id).toLowerCase();
      if (!targetCode) targetCode = userCode(targetUserId);
    }
    if (title.length < 3 || text.length < 3) return json(res, 400, { error: "title_and_body_required" });

    // B1: optional expiry. An ISO timestamp wins; otherwise a days count.
    // Empty/invalid means "never expires" (the old behaviour).
    const expiresAt = normalizeExpiresAt(body.expiresAt) || expiresAtFromDays(body.expiresInDays);

    const message = {
      id: `m-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      title,
      body: text,
      targetMode,
      audienceProfile: targetMode === "person" ? "" : audienceProfile,
      minVersionCode: targetMode === "person" ? 0 : minVersionCode,
      targetProfileId: targetMode === "person" ? targetProfileId : "",
      targetUserId: targetMode === "person" ? targetUserId : "",
      targetCode: targetMode === "person" ? targetCode : "",
      ...(expiresAt ? { expiresAt } : {}),
      at: new Date().toISOString(),
    };
    const messages = await appendMessage(message);

    // The audit row is best-effort after the publish succeeded; surface a warning instead of
    // pretending the publish failed. The app's receipts complete the same ledger later.
    let logWarning = "";
    try {
      await appendDeliveryEvents([{
        event: "published",
        messageId: message.id,
        messageTitle: title,
        targetMode,
        audienceProfile: message.audienceProfile,
        minVersionCode: message.minVersionCode,
        targetProfileId: message.targetProfileId,
        targetCode: message.targetCode,
      }]);
    } catch (error) {
      logWarning = "delivery_log_unavailable";
      console.error("delivery log publish write failed", error?.message || "unknown");
    }
    await appendAdminAudit([{
      actor: process.env.ADMIN_USERNAME || "admin", action: "message_publish", outcome: "ok",
      detail: `${title.slice(0, 60)} (${targetMode}${expiresAt ? `, expires ${expiresAt.slice(0, 10)}` : ""})`,
    }]);
    return json(res, 200, { ok: true, messages, logWarning });
  } catch {
    return json(res, 500, { error: "messages_failed" });
  }
};

/** B8: proxied admin-only read of one private support image. */
async function supportImage(req, res) {
  const pathname = String(req.query?.image || "").trim();
  const parsed = parseSupportImagePath(pathname);
  if (!parsed) return json(res, 400, { error: "invalid_image" });
  try {
    const bytes = await readPrivateBlobBytes(pathname);
    if (!bytes) return json(res, 404, { error: "image_not_found" });
    const mime = sniffImageMime(bytes);
    if (!mime) return json(res, 400, { error: "invalid_image_content" });
    res.setHeader("Content-Type", mime);
    res.setHeader("Cache-Control", "no-store");
    res.setHeader("X-Content-Type-Options", "nosniff");
    res.setHeader("Content-Security-Policy", "default-src 'none'; sandbox");
    res.statusCode = 200;
    return res.end(Buffer.from(bytes));
  } catch {
    return json(res, 503, { error: "image_unavailable" });
  }
}

/**
 * B5 (SA-P2-2): admin deletion of one user (or up to 50 in bulk) — user row,
 * support messages + their image blobs, delivery receipts, and the sync
 * snapshot + sync account for that user's profile id. The registry keeps a
 * `deleted_by_admin` audit event; the admin audit log keeps the action.
 * Single delete confirms with confirm:"DELETE"; bulk with confirm:"DELETE ALL".
 */
async function deleteUsers(req, res, body) {
  const actor = process.env.ADMIN_USERNAME || "admin";
  const bulk = body.action === "deleteUsers";
  const expectedConfirm = bulk ? "DELETE ALL" : "DELETE";
  if (String(body.confirm || "") !== expectedConfirm) {
    return json(res, 400, { error: "confirm_required", confirm: expectedConfirm });
  }
  const ids = bulk
    ? (Array.isArray(body.userIds) ? body.userIds : []).map((id) => String(id || "").trim().toLowerCase()).filter(Boolean).slice(0, 50)
    : [String(body.userId || "").trim().toLowerCase()].filter(Boolean);
  if (!ids.length) return json(res, 400, { error: "user_required" });
  if (bulk && ids.length > 50) return json(res, 400, { error: "too_many_users" });
  const users = await readUsers();
  const results = [];
  for (const id of ids) {
    const existing = users.find((item) => String(item.id || "").toLowerCase() === id);
    if (!existing) {
      results.push({ id, deleted: false, error: "user_not_found" });
      continue;
    }
    const profileId = normalizeProfileId(existing.profileId || existing.userId);
    const deleted = await deleteUser(id);
    let syncDeleted = false;
    if (profileId) {
      try {
        await deleteSync(profileId);
        syncDeleted = await deleteAccount(profileId);
      } catch (error) {
        console.error("admin delete sync cleanup failed", error?.message || "unknown");
      }
    }
    try {
      await appendRegistrationEvents([{ event: "deleted_by_admin", uid: id, profileId }]);
    } catch (error) {
      console.error("admin delete audit failed", error?.message || "unknown");
    }
    results.push({ id, deleted, syncDeleted });
  }
  const deletedCount = results.filter((r) => r.deleted).length;
  await appendAdminAudit([{
    actor, action: bulk ? "user_delete_bulk" : "user_delete", outcome: "ok",
    detail: `${deletedCount} of ${ids.length} user(s) deleted${bulk ? " (bulk)" : ""}`,
  }]);
  return json(res, 200, { ok: true, results, deleted: deletedCount });
}
