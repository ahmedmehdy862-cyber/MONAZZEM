# تحديثات مُنظِّم (Vercel)

Arabic release page with a protected `/admin/` publishing panel and a `/api/latest` update feed consumed by the Android app. The feed carries **both** `storeUrl` and `apkUrl` (also under `app.apkUrl`), plus `packageName`, `versionCode`, `versionName`, `releasePageUrl`, `releaseNotes`, and the nested `app` record. The app update button follows the store flow; the site still publishes the APK URL for the download page and dashboard.

## Publish a store release

1. Increase `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Publish the signed app to the connected Android store from the store's developer console.
3. Open `/admin/`, sign in, enter the version shown in the store, release notes, APK URL, app description, and QUBIXStore page URL.
4. Publish the notice. The feed, download page, and store card then point users to the store listing and the APK URL from the record.

Local `assembleRelease` builds fail unless `~/.focusstudy/keystore.properties` and the referenced key are present. For local experiments only, pass `-PallowUnsignedRelease=true`; never use that opt-out for a published release.

## Shared release source and automation

`/api/latest` is the canonical MONAZZEM record. It feeds the Android update checker and the QUBIXStore `/api/latest` proxy. QUBIXStore's MONAZZEM card, description, version, and download link are rendered from this live response instead of local hard-coded metadata. The same response also carries a `history` array (newest first, one row per `versionCode`, capped at 30). Every successful publish or store-link update appends or refreshes that row in the public `focus-study/release-history.json` Blob record; `release-history.json` ships the current release as the seed so the page shows a history from this point forward. A history read failure never hides the current release. The same response also carries an `apps` array: the MONAZZEM record plus `com.focusstudy.store` (the phone store app). Both apps come from this deployment and the same Blob backend; the shipped `store-app-release.json` is the seed and an optional public `focus-study/store-app.json` Blob record can raise its version. The website renders both cards; the phone app compares each `packageName`/`versionCode` with the packages installed on the device. No new Vercel function is needed: the catalog is an additive field on `/api/latest`. The store app APK is served from `/downloads/focus-store-0.1.0.apk`.

For automatic publication, deploy this project with `FOCUS_RELEASE_SYNC_TOKEN` set to a long random secret and `BLOB_READ_WRITE_TOKEN` configured. Add the same token as GitHub repository secret `FOCUS_RELEASE_SYNC_TOKEN`, along with the Android signing secrets described in `../.github/workflows/focus-study-release.yml`. Push a tag in the form `focus-vX.Y.Z` matching `versionName` in `app/build.gradle.kts`. The workflow:

1. regenerates `public/admin/app.bundle.js` with `node build-admin.cjs` and fails if the committed bundle is stale;
2. validates the tag against `versionName`, checks that the new version is not older than `feed.versionCode` on `/api/latest` (an equal code with the same `versionName` is allowed for re-runs), and checks that `SYNC_TOKEN` is non-empty **before** creating or uploading the GitHub Release (there is no harmless validation endpoint on the sync API, so no validation POST is sent);
3. builds a signed APK, publishes the GitHub Release, then posts the version, notes, APK URL, and store URL to `/api/admin/publish-ci`;
4. verifies with a fresh GET that `/api/latest` reports the new `versionCode`/`versionName`.

CI publishing is idempotent: a lower `versionCode` is rejected, an identical full payload at the same `versionCode` keeps the existing unchanged response, and any changed field at the same `versionCode` is written as an overwrite. The feed and store card refresh within about a minute.

Manual publishing remains available in `/admin/`: upload the signed APK to a public HTTPS release host such as GitHub Releases, paste its `.apk` URL, and publish the same version and notes. Do not publish an APK built with a different signing key or package ID.

For Vercel CLI deployment from this directory:

```powershell
npm install
npm run build
npm run check
vercel deploy --prod
```

The admin credential hash and support inbox are written with `access: "private"` in Vercel Blob and read server-side with `BLOB_READ_WRITE_TOKEN`; the public feed and broadcast messages remain public. Records written to the public host by older versions are copied to the private host on the first read and their public copies are deleted. The admin account uses the production environment variables `ADMIN_USERNAME`, `ADMIN_PASSWORD`, and `ADMIN_SESSION_SECRET`. Keep these private. The first store URL must be saved through the admin panel before publishing a newer version. Installing from a store preserves app data only when the store listing uses the same package ID and a compatible signing certificate.

The current release is `0.29.0` (`versionCode` 37). The app icon and website mark use the same shield and bolt geometry as the in-app logo. The app's signing key is private and must remain consistent with the signing configuration used by the connected store. `/admin/messages` also shows a private registry of users introduced automatically by the app, with a one-click button to address any of them by code, and a card that chooses a separate sound for each notification type.

## Post-deploy smoke test

After every deployment, check the live feed and the admin page:

```powershell
curl.exe -fsS https://update-site-sigma.vercel.app/api/latest
curl.exe -fsS https://update-site-sigma.vercel.app/api/latest | findstr history
curl.exe -fsS -o NUL -w "admin HTTP %{http_code}`n" https://update-site-sigma.vercel.app/admin/
```

The first command must return JSON with the expected `versionCode`, `versionName`, and a non-empty `apkUrl`; the second must return HTTP 200. Do not include tokens or credentials in these requests.

## Local preview

Run the site through Vercel CLI from this directory with `vercel dev`. Deploying to a Vercel account is a separate publishing step.
