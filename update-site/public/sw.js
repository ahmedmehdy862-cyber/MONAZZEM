/* Minimal offline shell: the release feed always comes from the network so updates are instant. */
const SHELL = "monazem-shell-v2";
const ASSETS = [
  "/", "/manifest.webmanifest", "/brand.svg", "/brand-mark.svg",
  "/app/", "/app/index.html", "/app/data.js", "/app/app.js", "/privacy.html",
];

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(SHELL).then((cache) => cache.addAll(ASSETS)).catch(() => undefined));
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys().then((keys) => Promise.all(keys.filter((key) => key !== SHELL).map((key) => caches.delete(key))))
  );
});

self.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);
  if (event.request.method !== "GET" || url.pathname.startsWith("/api/")) return;
  event.respondWith(
    fetch(event.request)
      .then((response) => {
        const copy = response.clone();
        caches.open(SHELL).then((cache) => cache.put(event.request, copy)).catch(() => undefined);
        return response;
      })
      .catch(() => caches.match(event.request).then((hit) => hit || caches.match("/")))
  );
});
