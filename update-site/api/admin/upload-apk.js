const { handleUpload } = require("@vercel/blob/client");
const { requireAdmin, originIsSame, parseBody, json } = require("../../lib/admin-auth");
const { appendAdminAudit } = require("../../lib/admin-audit");

module.exports = async function uploadApk(req, res) {
  if (req.method !== "POST") return json(res, 405, { error: "method_not_allowed" });
  if (!process.env.BLOB_READ_WRITE_TOKEN) return json(res, 503, { error: "storage_not_configured" });
  try {
    const result = await handleUpload({
      request: req,
      body: parseBody(req.body),
      onBeforeGenerateToken: async (pathname) => {
        if (!originIsSame(req)) throw new Error("authentication_required");
        // B2: the uploading session must be current-generation and must have
        // cleared mustChange; a revoked or bootstrap session cannot upload.
        const gate = await requireAdmin(req);
        if (!gate.ok) throw new Error("authentication_required");
        if (!/^focus-study\/releases\/[0-9]+-[a-f0-9-]+\.apk$/i.test(pathname)) throw new Error("invalid_upload_path");
        // B10: content-type is client-declared; the real check is the ZIP
        // magic bytes at publish time plus the panel's pre-upload check.
        // B4: token issuance is audited (best-effort, never blocks upload).
        appendAdminAudit([{ actor: process.env.ADMIN_USERNAME || "admin", action: "apk_upload", outcome: "ok", detail: pathname }]).catch(() => {});
        return {
          allowedContentTypes: ["application/vnd.android.package-archive", "application/octet-stream"],
          maximumSizeInBytes: 200 * 1024 * 1024,
          addRandomSuffix: false,
          allowOverwrite: false,
          cacheControlMaxAge: 31536000
        };
      }
    });
    return json(res, 200, result);
  } catch (error) {
    const reason = error?.message === "authentication_required" ? "authentication_required" : "apk_upload_failed";
    return json(res, reason === "authentication_required" ? 401 : 400, { error: reason });
  }
};
