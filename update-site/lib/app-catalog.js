const fs = require("node:fs");
const path = require("node:path");

const STORE_APP_PATH = "focus-study/store-app.json";
const FALLBACK_PATH = path.join(__dirname, "..", "store-app-release.json");
const FIELDS = [
  "id", "name", "packageName", "versionCode", "versionName",
  "storeUrl", "apkUrl", "description", "releasePageUrl", "releaseNotes", "publishedAt",
];

function httpsUrl(value, label) {
  const text = String(value ?? "").trim();
  if (!text) return "";
  let parsed;
  try { parsed = new URL(text); } catch { throw new Error(`${label}must_be_https`); }
  if (parsed.protocol !== "https:" || !parsed.hostname || parsed.username || parsed.password) {
    throw new Error(`${label}must_be_https`);
  }
  return text;
}

/** Only known catalog fields are returned, so a malformed Blob record cannot inject data. */
function normalizeApp(raw) {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
  const app = {};
  for (const field of FIELDS) {
    app[field] = field === "versionCode"
      ? Number(raw[field] || 0)
      : String(raw[field] ?? "").trim();
  }
  if (!Number.isSafeInteger(app.versionCode) || app.versionCode < 1) return null;
  if (!app.packageName || !app.versionName) return null;
  app.storeUrl = httpsUrl(app.storeUrl, "storeUrl_");
  app.apkUrl = httpsUrl(app.apkUrl, "apkUrl_");
  app.releasePageUrl = httpsUrl(app.releasePageUrl, "releasePageUrl_");
  return app;
}

async function readStoreAppRelease() {
  let shipped = null;
  try { shipped = normalizeApp(JSON.parse(fs.readFileSync(FALLBACK_PATH, "utf8"))); }
  catch { shipped = null; }

  let stored = null;
  if (process.env.BLOB_READ_WRITE_TOKEN) {
    try {
      const { get } = await import("@vercel/blob");
      const result = await get(STORE_APP_PATH, { access: "public" });
      if (result?.statusCode === 200 && result.stream) {
        stored = normalizeApp(JSON.parse(await new Response(result.stream).text()));
      }
    } catch (error) {
      console.error("Unable to read the store app record from Blob storage", error?.message || "unknown error");
    }
  }
  if (!stored) return shipped || {};
  if (!shipped || stored.versionCode > shipped.versionCode) return stored;
  if (shipped.versionCode > stored.versionCode) return shipped;
  return FIELDS.every((field) => stored[field] === shipped[field]) ? shipped : stored;
}

function filterHistory(history, packageName) {
  const rows = Array.isArray(history) ? history : [];
  const own = rows.filter((row) => row?.packageName === packageName);
  return (own.length ? own : rows).map((row) => {
    const app = normalizeApp(row);
    return app ? {
      versionCode: app.versionCode,
      versionName: app.versionName,
      releaseNotes: app.releaseNotes,
      publishedAt: app.publishedAt,
      storeUrl: app.storeUrl,
      apkUrl: app.apkUrl,
    } : null;
  }).filter(Boolean);
}

/**
 * The phone store app reads the same feed as the website. Adding `apps` here keeps one deployment,
 * one Blob backend and one publish flow; existing clients simply ignore the extra field.
 */
function buildAppsFeed({ latest, history, storeApp }) {
  const apps = [];
  const main = normalizeApp({
    id: "focus-study",
    name: "مُنظِّم",
    packageName: latest?.packageName,
    versionCode: latest?.versionCode,
    versionName: latest?.versionName,
    storeUrl: latest?.storeUrl || process.env.FOCUS_STORE_URL,
    apkUrl: latest?.apkUrl,
    description: latest?.app?.description || latest?.description,
    releasePageUrl: latest?.releasePageUrl,
    releaseNotes: latest?.releaseNotes,
  });
  if (main) apps.push({ ...main, history: filterHistory(history, main.packageName) });

  const store = normalizeApp(storeApp);
  if (store) apps.push({ ...store, history: filterHistory([store], store.packageName) });
  return apps;
}

async function readCatalog({ latest, history }) {
  const storeApp = await readStoreAppRelease();
  return buildAppsFeed({ latest, history, storeApp });
}

module.exports = {
  STORE_APP_PATH, FALLBACK_PATH, FIELDS,
  normalizeApp, readStoreAppRelease, filterHistory, buildAppsFeed, readCatalog,
};
