/**
 * Local regression test for the public release history and its presence in `/api/latest`.
 * No Blob token, network or credentials are involved:
 *   npm run test:history
 */
const assert = require("node:assert/strict");
const path = require("node:path");

const ROOT = path.join(__dirname, "..");
const real = require("../lib/release-history");

function loadMocked(relativeModule, mocks) {
  const abs = path.resolve(ROOT, relativeModule);
  const dir = path.dirname(abs);
  const saved = new Map();
  for (const [specifier, exports] of Object.entries(mocks)) {
    const dep = require.resolve(path.resolve(dir, specifier));
    saved.set(dep, require.cache[dep]);
    require.cache[dep] = { id: dep, filename: dep, loaded: true, exports, children: [], paths: [] };
  }
  delete require.cache[abs];
  try {
    return require(abs);
  } finally {
    for (const [dep, previous] of saved) {
      if (previous) require.cache[dep] = previous;
      else delete require.cache[dep];
    }
    delete require.cache[abs];
  }
}

function release(code, version, notes, publishedAt = "") {
  return {
    packageName: "com.focusstudy.poc", versionCode: code, versionName: version,
    storeUrl: "https://qubixstore.vercel.app/focus-study-updates",
    apkUrl: `https://example.com/app-${code}.apk`, description: "وصف",
    releasePageUrl: "https://example.com/#download", releaseNotes: notes, publishedAt,
  };
}

let passed = 0;
async function test(name, fn) {
  await fn();
  passed += 1;
  console.log(`ok - ${name}`);
}

async function main() {
  await test("only whitelisted fields survive normalization", async () => {
    const entry = real.normalizeEntry({ ...release(38, "0.30.0", "ملاحظات"), secret: "لا", notes: "خاصة" });
    assert.equal(entry.versionCode, 38);
    assert.equal(entry.secret, undefined);
    assert.equal(entry.notes, undefined);
    assert.equal(real.normalizeEntry({ versionCode: 0, versionName: "" }), null);
  });

  await test("history is newest-first, one row per version and capped", async () => {
    const entries = real.mergeHistory(
      [release(37, "0.29.0", "الأولى", "2026-11-19T11:23:12+02:00"), release(36, "0.28.0", "الأقدم", "2026-11-01T00:00:00Z")],
      release(38, "0.30.0", "الجديدة", "2026-11-21T10:00:00Z")
    );
    assert.deepEqual(entries.map((item) => item.versionCode), [38, 37, 36]);
    const bulk = real.mergeHistory(
      Array.from({ length: real.MAX_ENTRIES + 8 }, (_, i) => release(i + 1, `0.0.${i + 1}`, "ن")), null
    );
    assert.equal(bulk.length, real.MAX_ENTRIES);
    assert.equal(bulk[0].versionCode, real.MAX_ENTRIES + 8);
  });

  await test("a re-publish refreshes notes but keeps the original date", async () => {
    const original = release(37, "0.29.0", "القديمة", "2026-11-19T11:23:12+02:00");
    const updated = real.mergeHistory([original], { ...original, releaseNotes: "الجديدة", publishedAt: "" });
    assert.equal(updated.length, 1);
    assert.equal(updated[0].releaseNotes, "الجديدة");
    assert.equal(updated[0].publishedAt, "2026-11-19T11:23:12+02:00");
  });

  await test("the seed ships with the current release and no Blob token is needed", async () => {
    const saved = process.env.BLOB_READ_WRITE_TOKEN;
    delete process.env.BLOB_READ_WRITE_TOKEN;
    try {
      const entries = await real.readHistory();
      assert.equal(entries.length >= 1, true);
      assert.equal(entries[0].versionCode, 37);
      assert.equal(entries[0].versionName, "0.29.0");
    } finally {
      if (saved) process.env.BLOB_READ_WRITE_TOKEN = saved;
    }
  });

  await test("the public feed carries history without changing the latest release", async () => {
    const handler = loadMocked("api/latest.js", {
      "../lib/release-store": { readLatestRelease: async () => release(37, "0.29.0", "الأخيرة") },
      "../lib/release-history": { readHistory: async () => [release(37, "0.29.0", "الأخيرة", "2026-11-19T11:23:12+02:00")] },
      "../lib/app-catalog": { readCatalog: async () => [] },
    });
    const res = { headers: {}, status(code) { this.statusCode = code; return this; }, json(value) { this.body = value; return this; }, setHeader(k, v) { this.headers[k] = v; } };
    await handler({ method: "GET", headers: hostHeaders() }, res);
    assert.equal(res.statusCode, 200);
    assert.equal(res.body.versionCode, 37);
    assert.equal(res.body.history.length, 1);
    assert.equal(res.body.history[0].versionName, "0.29.0");
    assert.equal(res.body.app.name, "مُنظِّم");
  });

  await test("a history read failure never hides the current release", async () => {
    const handler = loadMocked("api/latest.js", {
      "../lib/release-store": { readLatestRelease: async () => release(37, "0.29.0", "الأخيرة") },
      "../lib/release-history": { readHistory: async () => { throw new Error("blob down"); } },
      "../lib/app-catalog": { readCatalog: async () => [] },
    });
    const res = { headers: {}, status(code) { this.statusCode = code; return this; }, json(value) { this.body = value; return this; }, setHeader(k, v) { this.headers[k] = v; } };
    await handler({ method: "GET", headers: hostHeaders() }, res);
    assert.equal(res.statusCode, 200);
    assert.deepEqual(res.body.history, []);
  });

  console.log(`release history tests: ${passed} passed`);
}

function hostHeaders() {
  return { host: "update-site-sigma.vercel.app" };
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
