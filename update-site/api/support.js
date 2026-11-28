const { generateClientTokenFromReadWriteToken } = require("@vercel/blob/client");
const { appendSupport, readInbox, attachSupportImages } = require("../lib/messaging-store");
const { parseBody, setSecurityHeaders } = require("../lib/admin-auth");
const { createFixedWindowLimiter } = require("../lib/rate-limit");
const { storageToken } = require("../lib/blob-access");
const {
  ALLOWED_IMAGE_TYPES, MAX_IMAGE_BYTES, MAX_IMAGES_PER_MESSAGE, UPLOAD_WINDOW_MS,
  imagePathnameFor, parseSupportImagePath, validSupportId,
  sniffImageMime, readPrivateBlobHead,
} = require("../lib/support-images");

// B7 (SA-P2-7): the unauthenticated inbox finally has a limit — 10/IP +
// 5/uid per 15 min, same shape as registration. In-memory (see lib/rate-limit.js).
const supportLimiter = createFixedWindowLimiter("support");

const cut = (value, max) => String(value || "").trim().slice(0, max);

/** Public: a user writes to the team; the message lands in the admin inbox. */
module.exports = async function support(request, response) {
  if (request.method !== "POST") {
    response.setHeader("Allow", "POST");
    return response.status(405).json({ error: "method_not_allowed" });
  }
  setSecurityHeaders(response);
  const body = parseBody(request.body);
  const action = String(body.action || "");
  if (action === "upload-token") return uploadToken(body, response);
  if (action === "confirm-images") return confirmImages(body, response);
  const gate = supportLimiter.check(request, cut(body.uid, 64));
  if (!gate.allowed) {
    response.setHeader("Retry-After", String(gate.retryAfter));
    return response.status(429).json({ error: "too_many_requests" });
  }
  const text = cut(body.body, 2000);
  if (text.length < 5) {
    supportLimiter.record(gate.keys);
    return response.status(400).json({ error: "message_too_short" });
  }
  try {
    const item = {
      id: `s-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      name: cut(body.name, 60),
      contact: cut(body.contact, 120),
      body: text,
      version: cut(body.version, 32),
      uid: cut(body.uid, 64),
      images: [],
      at: new Date().toISOString(),
      status: "new",
    };
    await appendSupport(item);
    supportLimiter.record(gate.keys);
    return response.status(200).json({ ok: true, id: item.id, supportId: item.id });
  } catch (error) {
    console.error("support failed", error?.message || "unknown");
    return response.status(503).json({ error: "support_unavailable" });
  }
};

/**
 * B8 — step 1 of the agreed contract: a pathname-scoped, 15-minute upload
 * token. The filename is server-chosen (UUID); the client must upload to
 * exactly this pathname with @vercel/blob/client `upload(pathname, file,
 * {access:"private", token})`. Binds to the server-issued support id +
 * matching uid, within 15 minutes of the text message.
 */
async function uploadToken(body, response) {
  const supportId = cut(body.supportId, 64);
  const uid = cut(body.uid, 64);
  const contentType = String(body.contentType || "").trim().toLowerCase();
  if (!validSupportId(supportId)) return response.status(400).json({ error: "invalid_support_id" });
  if (!uid || !ALLOWED_IMAGE_TYPES.includes(contentType)) {
    return response.status(400).json({ error: "invalid_upload_request" });
  }
  const token = storageToken("private");
  if (!token) return response.status(503).json({ error: "storage_not_configured" });
  try {
    const items = await readInbox();
    const item = items.find((entry) => entry.id === supportId);
    if (!item) return response.status(404).json({ error: "support_not_found" });
    if (String(item.uid || "") !== uid) return response.status(403).json({ error: "support_uid_mismatch" });
    if (Date.now() - Date.parse(item.at || "") > UPLOAD_WINDOW_MS) {
      return response.status(410).json({ error: "upload_window_expired" });
    }
    const existing = Array.isArray(item.images) ? item.images.length : 0;
    if (existing >= MAX_IMAGES_PER_MESSAGE) return response.status(409).json({ error: "too_many_images" });
    const pathname = imagePathnameFor(supportId, contentType);
    const clientToken = await generateClientTokenFromReadWriteToken({
      token,
      pathname,
      allowedContentTypes: [contentType],
      maximumSizeInBytes: MAX_IMAGE_BYTES,
      validUntil: Date.now() + UPLOAD_WINDOW_MS,
      addRandomSuffix: false,
      allowOverwrite: false,
    });
    return response.status(200).json({
      ok: true,
      token: clientToken,
      pathname,
      contentType,
      maxSizeBytes: MAX_IMAGE_BYTES,
      expiresAt: new Date(Date.now() + UPLOAD_WINDOW_MS).toISOString(),
    });
  } catch (error) {
    console.error("support upload token failed", error?.message || "unknown");
    return response.status(503).json({ error: "support_unavailable" });
  }
}

/**
 * B8 — step 2: after the direct upload, the server confirms and stores the
 * refs. Each blob is fetched and its MIME sniffed from magic bytes (never
 * trusted from the client), its size checked, and its pathname checked
 * against the support id. Unconfirmed uploads are never referenced.
 */
async function confirmImages(body, response) {
  const supportId = cut(body.supportId, 64);
  const uid = cut(body.uid, 64);
  if (!validSupportId(supportId) || !uid) return response.status(400).json({ error: "invalid_confirm_request" });
  const requested = Array.isArray(body.images) ? body.images : [];
  if (!requested.length || requested.length > MAX_IMAGES_PER_MESSAGE) {
    return response.status(400).json({ error: "invalid_images" });
  }
  try {
    const items = await readInbox();
    const item = items.find((entry) => entry.id === supportId);
    if (!item) return response.status(404).json({ error: "support_not_found" });
    if (String(item.uid || "") !== uid) return response.status(403).json({ error: "support_uid_mismatch" });
    if (Date.now() - Date.parse(item.at || "") > UPLOAD_WINDOW_MS) {
      return response.status(410).json({ error: "upload_window_expired" });
    }
    const existing = Array.isArray(item.images) ? item.images.length : 0;
    if (existing + requested.length > MAX_IMAGES_PER_MESSAGE) {
      return response.status(409).json({ error: "too_many_images" });
    }
    const confirmed = [];
    for (const image of requested) {
      const pathname = String(image?.pathname || "").trim();
      const declared = String(image?.contentType || "").trim().toLowerCase();
      const parsed = parseSupportImagePath(pathname);
      if (!parsed || parsed.supportId !== supportId) {
        return response.status(400).json({ error: "invalid_image_path" });
      }
      if (!ALLOWED_IMAGE_TYPES.includes(declared)) {
        return response.status(400).json({ error: "invalid_image_type" });
      }
      const head = await readPrivateBlobHead(pathname);
      if (!head) return response.status(404).json({ error: "image_not_found" });
      if (head.truncated) return response.status(413).json({ error: "image_too_large" });
      const sniffed = sniffImageMime(head.bytes);
      if (!sniffed || sniffed !== declared) {
        return response.status(400).json({ error: "image_type_mismatch" });
      }
      confirmed.push({ path: pathname, contentType: sniffed, sizeBytes: head.bytes.length });
    }
    const updated = await attachSupportImages(supportId, confirmed);
    if (!updated) return response.status(404).json({ error: "support_not_found" });
    return response.status(200).json({ ok: true, images: updated.images || [] });
  } catch (error) {
    console.error("support image confirm failed", error?.message || "unknown");
    return response.status(503).json({ error: "support_unavailable" });
  }
}
