const { put } = require("@vercel/blob");
const { requireAdmin, originIsSame, parseBody, json } = require("../../lib/admin-auth");
const { RELEASE_PATH, readLatestRelease, readFallbackRelease, normalizeWindows, normalizeSha256, releasePageHost, verifyApkMagic } = require("../../lib/release-store");
const { recordRelease } = require("../../lib/release-history");
const { appendAdminAudit } = require("../../lib/admin-audit");

module.exports = async function publishRelease(req, res) {
  if (req.method !== "POST") return json(res, 405, { error: "method_not_allowed" });
  if (!originIsSame(req)) return json(res, 403, { error: "origin_rejected" });
  const gate = await requireAdmin(req);
  if (!gate.ok) return json(res, gate.status, { error: gate.error });
  if (!process.env.BLOB_READ_WRITE_TOKEN) return json(res, 503, { error: "storage_not_configured" });

  try {
    const body = parseBody(req.body);

    // Keep the published record, the feed and the app on the same release. The deployment also
    // ships release.json, so a build that lands without a Blob record would otherwise leave the
    // site showing one version and the admin panel another.
    if (body.action === "sync") {
      const fallback = readFallbackRelease();
      let stored = null;
      try { stored = await readLatestRelease(); } catch { stored = null; }
      const storedCode = Number(stored?.versionCode || 0);
      const fallbackCode = Number(fallback?.versionCode || 0);
      const winner = fallbackCode >= storedCode ? fallback : stored;
      if (!winner || !Number(winner.versionCode)) return json(res, 400, { error: "release_feed_empty" });
      if (winner === stored) {
        return json(res, 200, { ok: true, changed: false, release: stored, source: "blob" });
      }
      await put(RELEASE_PATH, JSON.stringify(winner), {
        access: "public",
        addRandomSuffix: false,
        allowOverwrite: true,
        contentType: "application/json; charset=utf-8",
        cacheControlMaxAge: 60
      });
      await recordRelease(winner);
      await appendAdminAudit([{ actor: process.env.ADMIN_USERNAME || "admin", action: "release_sync", outcome: "ok", detail: `feed synced to ${winner.versionName} (${winner.versionCode})` }]);
      return json(res, 200, {
        ok: true,
        changed: true,
        release: winner,
        source: "release.json",
        replacedVersionCode: storedCode || null
      });
    }

    const versionCode = Number(body.versionCode);
    const versionName = String(body.versionName || "").trim();
    const releaseNotes = String(body.releaseNotes || "").trim().slice(0, 1600);
    const storeUrl = String(body.storeUrl || "").trim();
    const apkUrl = String(body.apkUrl || "").trim();
    const description = String(body.description || "نظّم مهامك وموادك وجلسات تركيزك وخطتك الدراسية في مساحة واحدة.").trim().slice(0, 500);
    // B9: SHA-256 of the APK when the publisher knows it (the admin panel
    // computes it from the uploaded file; CI sends it from the built APK).
    const sha256 = normalizeSha256(body.sha256);
    if (body.sha256 && !sha256) return json(res, 400, { error: "invalid_sha256" });
    if (!Number.isInteger(versionCode) || versionCode < 1 || !/^\d+\.\d+\.\d+$/.test(versionName) || !releaseNotes || !storeUrl || !apkUrl) {
      return json(res, 400, { error: "release_fields_required" });
    }

    const current = await readLatestRelease();
    if (versionCode <= Number(current.versionCode || 0)) return json(res, 409, { error: "version_code_must_increase", currentVersionCode: current.versionCode });
    // النشر اليدوي من اللوحة يجب ألا يُسقط نسخة ويندوز المرفقة من CI: لا حقل
    // لها في نموذج النشر، فتُحفَظ القيمة الحالية الصالحة كما هي. النسخة
    // المعروضة على الأزرار هي نسخة ملف MSI نفسه.
    const preservedWindows = normalizeWindows(current.windows);

    let parsedUrl;
    try { parsedUrl = new URL(storeUrl); } catch { return json(res, 400, { error: "invalid_store_url" }); }
    if (parsedUrl.protocol !== "https:" || !parsedUrl.hostname || parsedUrl.username || parsedUrl.password) return json(res, 400, { error: "invalid_store_url" });
    let parsedApkUrl;
    try { parsedApkUrl = new URL(apkUrl); } catch { return json(res, 400, { error: "invalid_apk_url" }); }
    if (parsedApkUrl.protocol !== "https:" || !parsedApkUrl.hostname || parsedApkUrl.username || parsedApkUrl.password || !parsedApkUrl.pathname.toLowerCase().endsWith(".apk")) return json(res, 400, { error: "invalid_apk_url" });

    // B10: the APK at the published URL must at least be a ZIP (PK\x03\x04).
    const magic = await verifyApkMagic(apkUrl);
    if (!magic.ok) return json(res, magic.error === "apk_not_a_zip" ? 400 : 503, { error: magic.error });

    // B10: releasePageUrl is pinned to the configured host (FOCUS_SITE_HOST)
    // when one is set, instead of trusting the request host header.
    const host = releasePageHost(req);
    if (!host) return json(res, 400, { error: "release_host_missing" });
    const release = {
      packageName: "com.focusstudy.poc",
      versionCode,
      versionName,
      storeUrl,
      apkUrl,
      description,
      releasePageUrl: `https://${host}/#download`,
      releaseNotes,
      ...(sha256 ? { sha256 } : {}),
      ...(preservedWindows ? { windows: preservedWindows } : {}),
      publishedAt: new Date().toISOString()
    };

    await put(RELEASE_PATH, JSON.stringify(release), {
      access: "public",
      addRandomSuffix: false,
      allowOverwrite: true,
      contentType: "application/json; charset=utf-8",
      cacheControlMaxAge: 60
    });
    await recordRelease(release);
    await appendAdminAudit([{ actor: process.env.ADMIN_USERNAME || "admin", action: "release_publish", outcome: "ok", detail: `${versionName} (${versionCode})${sha256 ? " sha256 set" : " no sha256"}` }]);
    return json(res, 200, { ok: true, release });
  } catch (error) {
    console.error("Release publish failed", error?.message || "unknown error");
    return json(res, 500, { error: "release_publish_failed" });
  }
};
