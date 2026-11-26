/** The phone store app shares /api/latest with the website; no Blob token or network is needed. */
const assert = require("node:assert/strict");
const path = require("node:path");

const ROOT = path.join(__dirname, "..");
const catalog = require("../lib/app-catalog");

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

function latestRelease() {
  return {
    packageName: "com.focusstudy.poc",
    versionCode: 37,
    versionName: "0.29.0",
    storeUrl: "https://qubixstore.vercel.app/focus-study-updates",
    apkUrl: "https://github.com/ahmedmehdy862-cyber/MONAZZEM/releases/download/focus-v0.29.0/MONAZZEM.apk",
    description: "وصف",
    releasePageUrl: "https://update-site-sigma.vercel.app/#download",
    releaseNotes: "ملاحظات",
    app: { name: "مُنظِّم", description: "وصف التطبيق" },
  };
}

function res() {
  return {
    headers: {}, statusCode: 200, body: null,
    status(code) { this.statusCode = code; return this; },
    json(value) { this.body = value; return this; },
    setHeader(key, value) { this.headers[key] = value; },
  };
}

function hostHeaders() {
  return { host: "update-site-sigma.vercel.app" };
}

let passed = 0;
async function test(name, fn) {
  await fn();
  passed += 1;
  console.log(`ok - ${name}`);
}

async function main() {
  await test("catalog normalization keeps only known fields and rejects insecure URLs", async () => {
    const app = catalog.normalizeApp({
      ...latestRelease(), id: "focus-study", name: "مُنظِّم", secret: "no", passphrase: "no",
    });
    assert.equal(app.id, "focus-study");
    assert.equal(app.secret, undefined);
    assert.equal(app.passphrase, undefined);
    assert.throws(() => catalog.normalizeApp({ ...latestRelease(), apkUrl: "http://example.com/a.apk" }), /apkUrl_must_be_https/);
    assert.equal(catalog.normalizeApp({ packageName: "x", versionName: "" }), null);
  });

  await test("the shipped store-app seed is available without a Blob token", async () => {
    const saved = process.env.BLOB_READ_WRITE_TOKEN;
    delete process.env.BLOB_READ_WRITE_TOKEN;
    try {
      const app = await catalog.readStoreAppRelease();
      assert.equal(app.packageName, "com.focusstudy.store");
      assert.equal(app.versionCode, 1);
      assert.equal(app.name, "متجر مُنظِّم");
      assert.match(app.apkUrl, /^https:\/\//);
    } finally {
      if (saved) process.env.BLOB_READ_WRITE_TOKEN = saved;
    }
  });

  await test("the feed lists both apps and keeps release history on the matching package", async () => {
    const saved = process.env.FOCUS_STORE_URL;
    delete process.env.FOCUS_STORE_URL;
    try {
      const apps = catalog.buildAppsFeed({
        latest: latestRelease(),
        history: [
          { packageName: "com.focusstudy.poc", versionCode: 37, versionName: "0.29.0", releaseNotes: "حالية", publishedAt: "2026-11-19T11:23:12+02:00" },
          { packageName: "com.other.app", versionCode: 99, versionName: "9.9.9", releaseNotes: "غريبة" },
        ],
        storeApp: await catalog.readStoreAppRelease(),
      });
      assert.deepEqual(apps.map((a) => a.packageName), ["com.focusstudy.poc", "com.focusstudy.store"]);
      assert.equal(apps[0].name, "مُنظِّم");
      assert.equal(apps[0].history.length, 1);
      assert.equal(apps[0].history[0].versionName, "0.29.0");
      assert.equal(apps[1].name, "متجر مُنظِّم");
      assert.equal(apps[1].history.length, 1);
      assert.equal(apps[1].history[0].versionCode, 1);
      assert.equal(JSON.stringify(apps).includes("passphrase"), false);
      assert.equal(JSON.stringify(apps).includes("BLOB_READ_WRITE_TOKEN"), false);
    } finally {
      if (saved) process.env.FOCUS_STORE_URL = saved;
    }
  });

  await test("/api/latest still returns the current release plus the shared app catalog", async () => {
    const handler = loadMocked("api/latest.js", {
      "../lib/release-store": { ...require("../lib/release-store"), readLatestRelease: async () => latestRelease() },
      "../lib/release-history": { readHistory: async () => [] },
      "../lib/app-catalog": { readCatalog: async () => catalog.buildAppsFeed({ latest: latestRelease(), history: [], storeApp: await catalog.readStoreAppRelease() }) },
    });
    const response = res();
    await handler({ method: "GET", headers: hostHeaders() }, response);
    assert.equal(response.statusCode, 200);
    assert.equal(response.body.versionCode, 37);
    assert.equal(response.body.app.name, "مُنظِّم");
    assert.equal(response.body.apps.length, 2);
    assert.equal(response.body.apps[1].packageName, "com.focusstudy.store");
  });

  await test("a catalog failure never hides the current release", async () => {
    const handler = loadMocked("api/latest.js", {
      "../lib/release-store": { ...require("../lib/release-store"), readLatestRelease: async () => latestRelease() },
      "../lib/release-history": { readHistory: async () => [] },
      "../lib/app-catalog": { readCatalog: async () => { throw new Error("catalog down"); } },
    });
    const response = res();
    await handler({ method: "GET", headers: hostHeaders() }, response);
    assert.equal(response.statusCode, 200);
    assert.equal(response.body.versionCode, 37);
    assert.deepEqual(response.body.apps, []);
  });

  console.log(`app catalog tests: ${passed} passed`);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
