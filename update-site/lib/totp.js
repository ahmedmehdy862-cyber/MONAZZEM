/**
 * TOTP (RFC 6238 / RFC 4226) with Node crypto only — no new dependency.
 *
 * The secret is base32 (RFC 4648, no padding in storage). Verification accepts
 * one 30-second step of clock skew either way. Backup codes are single-use:
 * only their SHA-256 hashes are stored, never the codes themselves.
 */
const crypto = require("node:crypto");

const BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
const STEP_SECONDS = 30;
const DIGITS = 6;

function base32Encode(buffer) {
  let bits = 0;
  let value = 0;
  let output = "";
  for (const byte of buffer) {
    value = (value << 8) | byte;
    bits += 8;
    while (bits >= 5) {
      output += BASE32_ALPHABET[(value >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  if (bits > 0) output += BASE32_ALPHABET[(value << (5 - bits)) & 31];
  return output;
}

function base32Decode(text) {
  const clean = String(text || "").trim().replace(/=+$/g, "").toUpperCase();
  if (!clean || /[^A-Z2-7]/.test(clean)) return null;
  let bits = 0;
  let value = 0;
  const bytes = [];
  for (const char of clean) {
    value = (value << 5) | BASE32_ALPHABET.indexOf(char);
    bits += 5;
    if (bits >= 8) {
      bytes.push((value >>> (bits - 8)) & 0xff);
      bits -= 8;
    }
  }
  return Buffer.from(bytes);
}

function generateSecret(bytes = 20) {
  return base32Encode(crypto.randomBytes(bytes));
}

function hotp(secret, counter) {
  const key = base32Decode(secret);
  if (!key) return null;
  const message = Buffer.alloc(8);
  message.writeBigUInt64BE(BigInt(counter));
  const digest = crypto.createHmac("sha1", key).update(message).digest();
  const offset = digest[digest.length - 1] & 0x0f;
  const code = ((digest[offset] & 0x7f) << 24)
    | ((digest[offset + 1] & 0xff) << 16)
    | ((digest[offset + 2] & 0xff) << 8)
    | (digest[offset + 3] & 0xff);
  return String(code % 10 ** DIGITS).padStart(DIGITS, "0");
}

function totp(secret, now = Date.now()) {
  return hotp(secret, Math.floor(now / 1000 / STEP_SECONDS));
}

/** Accepts the current step plus one step of skew either way. */
function verifyTotp(secret, code, now = Date.now()) {
  const clean = String(code || "").trim();
  if (!/^\d{6}$/.test(clean)) return false;
  const step = Math.floor(now / 1000 / STEP_SECONDS);
  for (const delta of [-1, 0, 1]) {
    const expected = hotp(secret, step + delta);
    if (expected && crypto.timingSafeEqual(Buffer.from(clean), Buffer.from(expected))) return true;
  }
  return false;
}

function otpauthUrl(secret, username) {
  const label = `MONAZZEM:${encodeURIComponent(username || "admin")}`;
  return `otpauth://totp/${label}?secret=${secret}&issuer=MONAZZEM&algorithm=SHA1&digits=6&period=30`;
}

// Backup codes: 10 characters from an unambiguous alphabet, shown once at enrolment.
const BACKUP_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
function generateBackupCodes(count = 8) {
  const codes = [];
  for (let i = 0; i < count; i += 1) {
    let code = "";
    const bytes = crypto.randomBytes(10);
    for (const byte of bytes) code += BACKUP_ALPHABET[byte % BACKUP_ALPHABET.length];
    codes.push(`${code.slice(0, 5)}-${code.slice(5)}`);
  }
  return codes;
}

function hashBackupCode(code) {
  return crypto.createHash("sha256").update(String(code || "").replace(/[^A-Za-z0-9]/g, "").toUpperCase()).digest("hex");
}

module.exports = {
  STEP_SECONDS, DIGITS,
  base32Encode, base32Decode, generateSecret, hotp, totp, verifyTotp,
  otpauthUrl, generateBackupCodes, hashBackupCode,
};
