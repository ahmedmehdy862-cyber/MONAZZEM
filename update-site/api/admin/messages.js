const { isAdmin, parseBody, json } = require("../../lib/admin-auth");
const {
  readMessages, appendMessage, removeMessage, readInbox, markSupport, readUsers,
  readNotificationSounds, writeNotificationSounds, normalizeProfileId,
  readDeliveryEvents, appendDeliveryEvents
} = require("../../lib/messaging-store");
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
      return json(res, 200, { messages: await readMessages() });
    }
    if (req.method === "DELETE") {
      const id = String(parseBody(req.body).id || "").trim();
      if (!id) return json(res, 400, { error: "id_required" });
      return json(res, 200, { messages: await removeMessage(id) });
    }
    if (req.method !== "POST") return json(res, 405, { error: "method_not_allowed" });
    const body = parseBody(req.body);
    if (body.markId) {
      const id = String(body.markId || "").trim();
      const status = ["new", "handled"].includes(body.status) ? body.status : "handled";
      return json(res, 200, { items: await markSupport(id, status) });
    }
    if (view === "sounds") return json(res, 200, await writeNotificationSounds(body.sounds));

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
    return json(res, 200, { ok: true, messages, logWarning });
  } catch {
    return json(res, 500, { error: "messages_failed" });
  }
};
