const { safeEqual, parseBody, json } = require("../../lib/admin-auth");
const { put } = require("@vercel/blob");
const { RELEASE_PATH, readLatestRelease, decideCiPublish, normalizeSha256, releasePageHost, verifyApkMagic } = require("../../lib/release-store");
const { recordRelease } = require("../../lib/release-history");
const { appendAdminAudit } = require("../../lib/admin-audit");

module.exports = async function publishFromCi(req, res) {
  if (req.method !== "POST") return json(res, 405, { error: "method_not_allowed" });
  const expected = process.env.FOCUS_RELEASE_SYNC_TOKEN;
  const supplied = String(req.headers.authorization || "").replace(/^Bearer\s+/i, "");
  if (!expected || !safeEqual(supplied, expected)) return json(res, 401, { error: "authentication_required" });
  if (!process.env.BLOB_READ_WRITE_TOKEN) return json(res, 503, { error: "storage_not_configured" });

  try {
    const body = parseBody(req.body);
    const versionCode = Number(body.versionCode);
    const versionName = String(body.versionName || "").trim();
    const releaseNotes = String(body.releaseNotes || "").trim().slice(0, 1600);
    const apkUrl = String(body.apkUrl || "").trim();
    const storeUrl = String(body.storeUrl || process.env.FOCUS_STORE_URL || "https://qubixstore.vercel.app/focus-study-updates").trim();
    const description = String(body.description || "نظّم مهامك وموادك وجلسات تركيزك وخطتك الدراسية في مساحة واحدة.").trim().slice(0, 500);
    // B9: SHA-256 of the APK when CI knows it (computed from the built APK).
    const sha256 = normalizeSha256(body.sha256);
    if (body.sha256 && !sha256) return json(res, 400, { error: "invalid_sha256" });
    if (!Number.isInteger(versionCode) || versionCode < 1 || !/^\d+\.\d+\.\d+$/.test(versionName) || !releaseNotes || !apkUrl) {
      return json(res, 400, { error: "release_fields_required" });
    }

    const validHttps = (value) => {
      try {
        const url = new URL(value);
        return url.protocol === "https:" && Boolean(url.hostname) && !url.username && !url.password;
      } catch { return false; }
    };
    let apk;
    try { apk = new URL(apkUrl); } catch { return json(res, 400, { error: "invalid_apk_url" }); }
    if (!validHttps(apkUrl) || !apk.pathname.toLowerCase().endsWith(".apk")) return json(res, 400, { error: "invalid_apk_url" });
    if (!validHttps(storeUrl)) return json(res, 400, { error: "invalid_store_url" });
    // Optional Windows MSI for the same release (windows-sync contract §9). Strict by design:
    // https, no credentials, a .msi path, an x.y.z version and a positive size (max 2 GiB).
    // Absent `windows` keeps the old Android-only behaviour byte-for-byte.
    let windows = null;
    if (body.windows !== undefined && body.windows !== null) {
      const candidate = body.windows;
      const downloadUrl = String(candidate?.downloadUrl || "").trim();
      const version = String(candidate?.version || "").trim();
      const sizeBytes = Number(candidate?.sizeBytes);
      const validWindows =
        candidate && typeof candidate === "object" && !Array.isArray(candidate) &&
        validHttps(downloadUrl) &&
        (() => { try { return new URL(downloadUrl).pathname.toLowerCase().endsWith(".msi"); } catch { return false; } })() &&
        /^\d+\.\d+\.\d+$/.test(version) &&
        Number.isSafeInteger(sizeBytes) && sizeBytes >= 1 && sizeBytes <= 2147483648;
      if (!validWindows) return json(res, 400, { error: "invalid_windows_release" });
      windows = { downloadUrl, version, sizeBytes };
    }
    // B10: releasePageUrl is pinned to the configured host (FOCUS_SITE_HOST)
    // when one is set, instead of trusting the request host header.
    const host = releasePageHost(req);
    if (!host) return json(res, 400, { error: "release_host_missing" });

    // `publishedAt` is added only on a real write so an identical re-run stays byte-for-byte
    // comparable with the shipped release.json.
    const release = {
      packageName: "com.focusstudy.poc", versionCode, versionName, storeUrl, apkUrl, description,
      releasePageUrl: `https://${host}/#download`, releaseNotes,
      ...(sha256 ? { sha256 } : {}),
      ...(windows ? { windows } : {}),
    };

    const current = await readLatestRelease();
    const decision = decideCiPublish(current, release);
    if (decision.action === "reject") {
      return json(res, 409, { error: decision.error, currentVersionCode: current.versionCode });
    }
    if (decision.action === "unchanged") {
      return json(res, 200, { ok: true, unchanged: true, release: current });
    }

    // B10: the APK at the published URL must at least be a ZIP (PK\x03\x04).
    // Checked only on a real write, so identical re-runs stay cheap.
    const magic = await verifyApkMagic(apkUrl);
    if (!magic.ok) return json(res, magic.error === "apk_not_a_zip" ? 400 : 503, { error: magic.error });

    const published = { ...release, publishedAt: new Date().toISOString() };
    await put(RELEASE_PATH, JSON.stringify(published), {
      access: "public", addRandomSuffix: false, allowOverwrite: true,
      contentType: "application/json; charset=utf-8", cacheControlMaxAge: 60
    });
    await recordRelease(published);
    await appendAdminAudit([{ actor: "ci", action: "release_publish", outcome: "ok", detail: `CI ${versionName} (${versionCode})${sha256 ? " sha256 set" : " no sha256"}` }]);
    return json(res, 200, {
      ok: true, packageName: release.packageName, versionCode, versionName, storeUrl, apkUrl,
      ...(sha256 ? { sha256 } : {}),
      ...(windows ? { windows } : {}),
    });
  } catch (error) {
    console.error("CI release sync failed", error?.message || "unknown error");
    return json(res, 500, { error: "release_publish_failed" });
  }
};
