const { readLatestRelease } = require("../lib/release-store");
const { readHistory } = require("../lib/release-history");
const { readCatalog } = require("../lib/app-catalog");

module.exports = async function latestRelease(request, response) {
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
  const storeUrl = String(release.storeUrl || process.env.FOCUS_STORE_URL || "https://qubixstore.vercel.app/focus-study-updates");
  const releasePageUrl = String(release.releasePageUrl || "").startsWith("https://") ? release.releasePageUrl : `${origin}${release.releasePageUrl || "/#download"}`;
  const feed = {
    packageName: release.packageName,
    versionCode: release.versionCode,
    versionName: release.versionName,
    storeUrl,
    apkUrl: String(release.apkUrl || ""),
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
      releaseNotes: String(release.releaseNotes || "")
    },
    releasePageUrl,
    releaseNotes: release.releaseNotes,
    history,
    apps
  };

  response.setHeader("Cache-Control", "public, s-maxage=60, stale-while-revalidate=300");
  response.setHeader("Content-Type", "application/json; charset=utf-8");
  return response.status(200).json(feed);
};
