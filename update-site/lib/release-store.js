const fs = require("node:fs");
const path = require("node:path");

const RELEASE_PATH = "focus-study/latest.json";
const FALLBACK_PATH = path.join(__dirname, "..", "release.json");

/**
 * Every field that makes up a published release. `publishedAt` is intentionally excluded: it is
 * generated per publish attempt, so it must not make an otherwise identical re-run look changed.
 */
const RELEASE_PAYLOAD_FIELDS = [
  "packageName",
  "versionCode",
  "versionName",
  "storeUrl",
  "apkUrl",
  "description",
  "releasePageUrl",
  "releaseNotes",
  // `windows` is an object, not a string (see normalizeReleasePayload); it carries the
  // Windows MSI download for the same release (windows-sync contract §8–§9).
  "windows",
];

/**
 * Normalizes the optional Windows release (`{downloadUrl, version, sizeBytes}`) to a fixed-shape
 * object, or `null` when absent/invalid. Invalid values never become a partial object: a bad
 * `windows` is the same as no `windows`, so an old stored record compares equal to a new one
 * that simply omits it.
 */
function normalizeWindows(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const downloadUrl = String(value.downloadUrl ?? "").trim();
  const version = String(value.version ?? "").trim();
  const sizeBytes = Number(value.sizeBytes || 0);
  if (!downloadUrl || !version || !Number.isSafeInteger(sizeBytes) || sizeBytes <= 0) return null;
  return { downloadUrl, version, sizeBytes };
}

function normalizeReleasePayload(release) {
  if (!release || typeof release !== "object" || Array.isArray(release)) return null;
  const normalized = {};
  for (const field of RELEASE_PAYLOAD_FIELDS) {
    const value = release[field];
    if (field === "windows") {
      normalized[field] = normalizeWindows(value);
    } else {
      normalized[field] = field === "versionCode"
        ? Number(value || 0)
        : String(value ?? "").trim();
    }
  }
  return normalized;
}

/** True only when all published fields match; the generated timestamp is ignored. */
function releasePayloadEquals(left, right) {
  const a = normalizeReleasePayload(left);
  const b = normalizeReleasePayload(right);
  if (!a || !b) return false;
  return RELEASE_PAYLOAD_FIELDS.every((field) => {
    // `windows` is an object: compare by value, so adding it later to the same versionCode
    // counts as a change (write) instead of looking identical to "no windows".
    if (field === "windows") return JSON.stringify(a.windows) === JSON.stringify(b.windows);
    return a[field] === b[field];
  });
}

/**
 * CI publish decision, kept pure so it can be checked without Vercel Blob or HTTP.
 *
 * - lower versionCode: reject (never roll back)
 * - equal versionCode + identical payload: unchanged (no Blob write)
 * - equal versionCode + any changed field: overwrite (never silently skipped)
 * - higher versionCode: write
 *
 * Adding a `windows` MSI to an already-published Android version is the equal-versionCode
 * overwrite case: no versionCode bump is needed, the payload simply differs.
 */
function decideCiPublish(current, incoming) {
  const currentCode = Number(current?.versionCode || 0);
  const incomingCode = Number(incoming?.versionCode || 0);
  if (incomingCode < currentCode) {
    return { action: "reject", error: "version_code_must_increase" };
  }
  if (incomingCode === currentCode && releasePayloadEquals(current, incoming)) {
    return { action: "unchanged" };
  }
  return { action: "write" };
}

/**
 * Highest release wins.
 *
 * The stored record is what the team publishes from the dashboard or CI, while every deployment
 * also ships release.json. A newer stored record always wins. When both carry the same
 * versionCode, the stored record wins only if it actually differs: that lets an equal-version
 * overwrite become visible, while an identical stored record keeps the existing shipped response
 * shape (including its lack of generated fields such as publishedAt).
 */
async function readLatestRelease() {
  let stored = null;
  if (process.env.BLOB_READ_WRITE_TOKEN) {
    try {
      const { get } = await import("@vercel/blob");
      const result = await get(RELEASE_PATH, { access: "public" });
      if (result?.statusCode === 200 && result.stream) {
        stored = JSON.parse(await new Response(result.stream).text());
      }
    } catch (error) {
      console.error("Unable to read the latest release record from Blob storage", error?.message || "unknown error");
    }
  }
  let shipped = null;
  try { shipped = JSON.parse(fs.readFileSync(FALLBACK_PATH, "utf8")); } catch { shipped = null; }
  if (!stored) return shipped || {};
  if (!shipped) return stored;
  const storedCode = Number(stored.versionCode || 0);
  const shippedCode = Number(shipped.versionCode || 0);
  if (storedCode > shippedCode) return stored;
  if (shippedCode > storedCode) return shipped;
  return releasePayloadEquals(stored, shipped) ? shipped : stored;
}

/** The copy shipped with the deployment; used when Blob has nothing newer. */
function readFallbackRelease() {
  return JSON.parse(fs.readFileSync(FALLBACK_PATH, "utf8"));
}

module.exports = {
  RELEASE_PATH,
  FALLBACK_PATH,
  RELEASE_PAYLOAD_FIELDS,
  normalizeWindows,
  normalizeReleasePayload,
  releasePayloadEquals,
  decideCiPublish,
  readLatestRelease,
  readFallbackRelease,
};
