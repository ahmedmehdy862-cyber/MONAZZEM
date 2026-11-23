const { appendSupport } = require("../lib/messaging-store");
const { parseBody } = require("../lib/admin-auth");

const cut = (value, max) => String(value || "").trim().slice(0, max);

/** Public: a user writes to the team; the message lands in the admin inbox. */
module.exports = async function support(request, response) {
  if (request.method !== "POST") {
    response.setHeader("Allow", "POST");
    return response.status(405).json({ error: "method_not_allowed" });
  }
  const body = parseBody(request.body);
  const text = cut(body.body, 2000);
  if (text.length < 5) return response.status(400).json({ error: "message_too_short" });
  try {
    const item = {
      id: `s-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      name: cut(body.name, 60),
      contact: cut(body.contact, 120),
      body: text,
      version: cut(body.version, 32),
      uid: cut(body.uid, 64),
      at: new Date().toISOString(),
      status: "new",
    };
    await appendSupport(item);
    return response.status(200).json({ ok: true, id: item.id });
  } catch (error) {
    console.error("support failed", error?.message || "unknown");
    return response.status(503).json({ error: "support_unavailable" });
  }
};
