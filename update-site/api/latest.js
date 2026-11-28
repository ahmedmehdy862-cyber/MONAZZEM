const { readLatestRelease, normalizeWindows, normalizeSha256 } = require("../lib/release-store");
const { readHistory } = require("../lib/release-history");
const { readCatalog } = require("../lib/app-catalog");
const { setSecurityHeaders } = require("../lib/admin-auth");

module.exports = async function latestRelease(request, response) {
  setSecurityHeaders(response);
  if (request.method !== "GET" && request.method !== "HEAD") {
    response.setHeader("Allow", "GET, HEAD");
    return response.status(405).json({ error: "method_not_allowed" });
  }

  const forwardedHost = request.headers["x-forwarded-host"] || request.headers.host;
  const host = String(forwardedHost || "").split(",")[0].trim();
  if (!host) {
    return response.status(500).json({ error: "release_feed_unavailable" });
  }

  const origin = `https://${host}`;
  let release;
  try { release = await readLatestRelease(); }
  catch { return response.status(503).json({ error: "release_feed_unavailable" }); }
  // History is additive: a history failure must never hide the current release from the app.
  let history = [];
  try { history = await readHistory(); }
  catch (error) { console.error("Release history unavailable", error?.message || "unknown error"); }
  // The phone store app and the website share this feed; a catalog failure must not hide releases.
  let apps = [];
  try { apps = await readCatalog({ latest: release, history }); }
  catch (error) { console.error("App catalog unavailable", error?.message || "unknown error"); }
  // The Windows MSI rides the same release: expose it only when the stored release carries
  // a well-formed `windows` object (windows-sync contract §8–§9). A malformed stored value is
  // omitted, never forwarded to clients.
  const windows = normalizeWindows(release.windows);
  const validWindows = windows && (() => {
    try {
      const url = new URL(windows.downloadUrl);
      return url.protocol === "https:" && !url.username && !url.password &&
        url.pathname.toLowerCase().endsWith(".msi") && /^\d+\.\d+\.\d+$/.test(windows.version);
    } catch { return false; }
  })() ? windows : null;
  const storeUrl = String(release.storeUrl || process.env.FOCUS_STORE_URL || "https://qubixstore.vercel.app/focus-study-updates");
  const releasePageUrl = String(release.releasePageUrl || "").startsWith("https://") ? release.releasePageUrl : `${origin}${release.releasePageUrl || "/#download"}`;
  // B9: the APK SHA-256 rides the feed when the release carries one.
  const sha256 = normalizeSha256(release.sha256);
  const feed = {
    packageName: release.packageName,
    versionCode: release.versionCode,
    versionName: release.versionName,
    storeUrl,
    apkUrl: String(release.apkUrl || ""),
    ...(sha256 ? { sha256 } : {}),
    app: {
      id: "focus-study",
      name: "مُنظِّم",
      category: "تعليم",
      developer: "مُنظِّم",
      description: String(release.description || "نظّم مهامك وموادك وجلسات تركيزك وخطتك الدراسية في مساحة واحدة."),
      versionCode: release.versionCode,
      versionName: release.versionName,
      apkUrl: String(release.apkUrl || ""),
      storeUrl,
      releaseNotes: String(release.releaseNotes || ""),
      ...(sha256 ? { sha256 } : {}),
      ...(validWindows ? { windows: validWindows } : {})
    },
    ...(validWindows ? { windows: validWindows } : {}),
    releasePageUrl,
    releaseNotes: release.releaseNotes,
    history,
    apps
  };

  response.setHeader("Cache-Control", "public, s-maxage=60, stale-while-revalidate=300");
  response.setHeader("Content-Type", "application/json; charset=utf-8");
  return response.status(200).json(feed);
};
