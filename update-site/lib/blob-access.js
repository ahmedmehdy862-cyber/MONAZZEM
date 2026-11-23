/**
 * Token routing for the two Blob stores connected to this project.
 *
 * The release feed and APK uploads stay in the original public store (`BLOB_READ_WRITE_TOKEN`) so
 * their URLs keep working. The user registry, support inbox, sound choices, delivery ledger,
 * sync snapshots and every other private record live in a dedicated private store
 * (`PRIVATE_READ_WRITE_TOKEN`). @vercel/blob rejects `access: "private"` on a public store, so a
 * private path must be called with the token of the private store.
 *
 * This module contains no secrets: it only selects which environment variable to pass.
 */
const PUBLIC_ACCESS = "public";
const PRIVATE_ACCESS = "private";

function storageToken(access) {
  return access === PRIVATE_ACCESS
    ? (process.env.PRIVATE_READ_WRITE_TOKEN || process.env.BLOB_READ_WRITE_TOKEN)
    : process.env.BLOB_READ_WRITE_TOKEN;
}

module.exports = { storageToken, PUBLIC_ACCESS, PRIVATE_ACCESS };
