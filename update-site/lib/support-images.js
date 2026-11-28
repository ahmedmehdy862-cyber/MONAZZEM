/**
 * Support-image validation (B8 / SA-P2-8), server side.
 *
 * Contract (agreed with Wave A):
 *   POST /api/support {action:"upload-token", supportId, uid, filename, contentType}
 *     -> {token, pathname, expiresAt, maxSizeBytes}
 *   client uploads direct to Blob with that pathname-scoped token
 *     (upload(pathname, file, {access:"private", token}))
 *   POST /api/support {action:"confirm-images", supportId, uid, images:[{pathname, contentType}]}
 *     -> server sniffs the stored bytes, then stores the refs on the inbox item.
 *
 * Validation never trusts the client: MIME is sniffed from magic bytes, the
 * filename is server-chosen (UUID), uploads bind to the server-issued support
 * id + matching uid within 15 minutes. EXIF/GPS stripping is client-side
 * (Wave A). Images live in the private Blob only; admin reads go through the
 * proxied GET /api/admin/messages?view=support-image&image=<pathname>.
 */
const crypto = require("node:crypto");
const { storageToken } = require("./blob-access");

const SUPPORT_IMAGES_PREFIX = "focus-study/support-images/";
const MAX_IMAGE_BYTES = 5 * 1024 * 1024;
const MAX_IMAGES_PER_MESSAGE = 4;
const UPLOAD_WINDOW_MS = 15 * 60 * 1000;
const ALLOWED_IMAGE_TYPES = ["image/jpeg", "image/png", "image/webp"];
const EXT_BY_TYPE = { "image/jpeg": "jpg", "image/png": "png", "image/webp": "webp" };
const TYPE_BY_EXT = { jpg: "image/jpeg", jpeg: "image/jpeg", png: "image/png", webp: "image/webp" };

const SUPPORT_ID_PATTERN = /^s-[0-9]+-[a-z0-9]+$/;
const IMAGE_PATH_PATTERN = /^focus-study\/support-images\/(s-[0-9]+-[a-z0-9]+)\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\.(jpg|jpeg|png|webp)$/;

function supportImagePathname(supportId) {
  return `${SUPPORT_IMAGES_PREFIX}${supportId}/${crypto.randomUUID()}.jpg`;
}

function imagePathnameFor(supportId, contentType) {
  const ext = EXT_BY_TYPE[contentType];
  if (!ext) return "";
  return `${SUPPORT_IMAGES_PREFIX}${supportId}/${crypto.randomUUID()}.${ext}`;
}

/** Returns {supportId, filename, ext} or null when the pathname is not ours. */
function parseSupportImagePath(pathname) {
  const match = IMAGE_PATH_PATTERN.exec(String(pathname || "").trim());
  if (!match) return null;
  return { supportId: match[1], filename: match[2], ext: match[3] };
}

function validSupportId(value) {
  return SUPPORT_ID_PATTERN.test(String(value || "").trim());
}

/** Sniffs magic bytes; returns the real MIME type or "" when not an allowed image. */
function sniffImageMime(bytes) {
  const b = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes || []);
  if (b.length >= 3 && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff) return "image/jpeg";
  if (b.length >= 8 && b[0] === 0x89 && b[1] === 0x50 && b[2] === 0x4e && b[3] === 0x47
    && b[4] === 0x0d && b[5] === 0x0a && b[6] === 0x1a && b[7] === 0x0a) return "image/png";
  if (b.length >= 12 && b[0] === 0x52 && b[1] === 0x49 && b[2] === 0x46 && b[3] === 0x46
    && b[8] === 0x57 && b[9] === 0x45 && b[10] === 0x42 && b[11] === 0x50) return "image/webp";
  return "";
}

/** ZIP local-file header PK\x03\x04 — minimum APK sanity check (B10). */
function hasZipMagic(bytes) {
  const b = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes || []);
  return b.length >= 4 && b[0] === 0x50 && b[1] === 0x4b && b[2] === 0x03 && b[3] === 0x04;
}

// Seam for tests: the default reader fetches the private Blob; tests replace it.
let blobReader = async (pathname) => {
  const { get } = await import("@vercel/blob");
  const result = await get(pathname, { access: "private", token: storageToken("private") });
  if (!result?.stream) return null;
  const buffer = Buffer.from(await new Response(result.stream).arrayBuffer());
  return buffer;
};

function setBlobReader(reader) { blobReader = reader; }
async function readPrivateBlobBytes(pathname) { return blobReader(pathname); }

/** Reads at most limit+1 bytes so an oversized file is detected without buffering it all. */
async function readPrivateBlobHead(pathname, limit = MAX_IMAGE_BYTES) {
  const bytes = await readPrivateBlobBytes(pathname);
  if (!bytes) return null;
  return { bytes: bytes.subarray(0, limit + 1), truncated: bytes.length > limit };
}

module.exports = {
  SUPPORT_IMAGES_PREFIX, MAX_IMAGE_BYTES, MAX_IMAGES_PER_MESSAGE, UPLOAD_WINDOW_MS,
  ALLOWED_IMAGE_TYPES, EXT_BY_TYPE, TYPE_BY_EXT,
  supportImagePathname, imagePathnameFor, parseSupportImagePath, validSupportId,
  sniffImageMime, hasZipMagic, readPrivateBlobBytes, readPrivateBlobHead, setBlobReader,
};
