const fs = require("node:fs");
const path = require("node:path");

const HISTORY_PATH = "focus-study/release-history.json";
const FALLBACK_PATH = path.join(__dirname, "..", "release-history.json");
const MAX_ENTRIES = 30;
const FIELDS = [
  "packageName", "versionCode", "versionName", "storeUrl", "apkUrl",
  "description", "releasePageUrl", "releaseNotes", "publishedAt",
];

/** Only known feed fields are kept, so a malformed Blob record cannot inject arbitrary data. */
function normalizeEntry(release) {
  if (!release || typeof release !== "object" || Array.isArray(release)) return null;
  const entry = {};
  for (const field of FIELDS) {
    entry[field] = field === "versionCode"
      ? Number(release[field] || 0)
      : String(release[field] ?? "").trim();
  }
  if (!Number.isSafeInteger(entry.versionCode) || entry.versionCode < 1 || !entry.versionName) return null;
  return entry;
}

function compareEntries(a, b) {
  return b.versionCode - a.versionCode
    || String(b.publishedAt || "").localeCompare(String(a.publishedAt || ""))
    || a.versionName.localeCompare(b.versionName);
}

/**
 * One entry per version code. A newer publish refreshes links and notes for the same version,
 * while keeping the original publish date. Newest versions come first and the list is capped.
 */
function mergeHistory(existing, incoming, max = MAX_ENTRIES) {
  const byCode = new Map();
  for (const raw of Array.isArray(existing) ? existing : []) {
    const item = normalizeEntry(raw);
    if (item) byCode.set(item.versionCode, item);
  }
  const next = normalizeEntry(incoming);
  if (next) {
    const previous = byCode.get(next.versionCode);
    const publishedAt = next.publishedAt || previous?.publishedAt || "";
    byCode.set(next.versionCode, { ...(previous || {}), ...next, publishedAt });
  }
  return [...byCode.values()].sort(compareEntries).slice(0, Math.max(1, Math.min(max, MAX_ENTRIES)));
}

async function readBlobHistory() {
  if (!process.env.BLOB_READ_WRITE_TOKEN) return null;
  try {
    const { get } = await import("@vercel/blob");
    const result = await get(HISTORY_PATH, { access: "public" });
    if (result?.statusCode === 200 && result.stream) {
      const record = JSON.parse(await new Response(result.stream).text());
      return Array.isArray(record) ? record : record?.entries;
    }
  } catch (error) {
    console.error("Unable to read the release history from Blob storage", error?.message || "unknown error");
  }
  return null;
}

function readFallbackHistory() {
  try {
    const record = JSON.parse(fs.readFileSync(FALLBACK_PATH, "utf8"));
    return Array.isArray(record) ? record : record?.entries;
  } catch {
    return null;
  }
}

/** The shipped seed covers releases made before history recording existed. */
async function readHistory() {
  let entries = mergeHistory(readFallbackHistory(), null);
  const stored = await readBlobHistory();
  for (const item of Array.isArray(stored) ? stored : []) entries = mergeHistory(entries, item);
  return entries;
}

async function writeHistory(entries) {
  if (!process.env.BLOB_READ_WRITE_TOKEN) return false;
  const { put } = await import("@vercel/blob");
  await put(HISTORY_PATH, JSON.stringify({ entries }), {
    access: "public",
    addRandomSuffix: false,
    allowOverwrite: true,
    contentType: "application/json; charset=utf-8",
    cacheControlMaxAge: 60,
  });
  return true;
}

/** Called after every successful publish or store-link update. Never throws into the response path. */
async function recordRelease(release) {
  try {
    const entries = mergeHistory(await readHistory(), release);
    await writeHistory(entries);
    return entries;
  } catch (error) {
    console.error("Release history update failed", error?.message || "unknown error");
    return null;
  }
}

module.exports = {
  HISTORY_PATH, FALLBACK_PATH, MAX_ENTRIES, FIELDS,
  normalizeEntry, mergeHistory, readHistory, writeHistory, recordRelease,
};
