const { put } = require("@vercel/blob");
const { requireAdmin, originIsSame, parseBody, json } = require("../../lib/admin-auth");
const { RELEASE_PATH, readLatestRelease, releasePageHost } = require("../../lib/release-store");
const { recordRelease } = require("../../lib/release-history");
const { appendAdminAudit } = require("../../lib/admin-audit");

module.exports = async function saveStoreLink(req, res) {
  if (req.method !== "POST") return json(res, 405, { error: "method_not_allowed" });
  if (!originIsSame(req)) return json(res, 403, { error: "origin_rejected" });
  const gate = await requireAdmin(req);
  if (!gate.ok) return json(res, gate.status, { error: gate.error });
  if (!process.env.BLOB_READ_WRITE_TOKEN) return json(res, 503, { error: "storage_not_configured" });

  try {
    const body = parseBody(req.body);
    const storeUrl = String(body.storeUrl || "").trim();
    const apkUrl = String(body.apkUrl || "").trim();
    let parsedUrl;
    try { parsedUrl = new URL(storeUrl); } catch { return json(res, 400, { error: "invalid_store_url" }); }
    if (parsedUrl.protocol !== "https:" || !parsedUrl.hostname || parsedUrl.username || parsedUrl.password) return json(res, 400, { error: "invalid_store_url" });
    if (apkUrl) {
      let parsedApkUrl;
      try { parsedApkUrl = new URL(apkUrl); } catch { return json(res, 400, { error: "invalid_apk_url" }); }
      if (parsedApkUrl.protocol !== "https:" || !parsedApkUrl.hostname || parsedApkUrl.username || parsedApkUrl.password || !parsedApkUrl.pathname.toLowerCase().endsWith(".apk")) return json(res, 400, { error: "invalid_apk_url" });
    }
    const current = await readLatestRelease();
    // B10: releasePageUrl is pinned to the configured host (FOCUS_SITE_HOST)
    // when one is set, instead of trusting the request host header.
    const host = releasePageHost(req);
    if (!host) return json(res, 400, { error: "release_host_missing" });
    const release = { ...current, storeUrl, ...(apkUrl ? { apkUrl } : {}), releasePageUrl: `https://${host}/#download`, publishedAt: new Date().toISOString() };
    await put(RELEASE_PATH, JSON.stringify(release), {
      access: "public",
      addRandomSuffix: false,
      allowOverwrite: true,
      contentType: "application/json; charset=utf-8",
      cacheControlMaxAge: 60
    });
    // A store-link change must not rewrite the original publish date in the history row.
    await recordRelease({ ...release, publishedAt: "" });
    await appendAdminAudit([{ actor: process.env.ADMIN_USERNAME || "admin", action: "store_link", outcome: "ok", detail: storeUrl.slice(0, 120) }]);
    return json(res, 200, { ok: true, storeUrl });
  } catch (error) {
    console.error("Store URL update failed", error?.message || "unknown error");
    return json(res, 500, { error: "store_link_save_failed" });
  }
};
