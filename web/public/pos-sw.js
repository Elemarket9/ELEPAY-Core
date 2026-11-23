/* ELEPAY merchant POS app shell only; do not cache API, checkout or private responses. */
const CACHE = "elepay-pos-shell-v1";
self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.add("/merchant"))
    .then(() => self.skipWaiting()));
});
self.addEventListener("activate", (event) => {
  event.waitUntil(caches.keys().then((keys) => Promise.all(keys
    .filter((key) => key.startsWith("elepay-pos-shell-") && key !== CACHE)
    .map((key) => caches.delete(key)))).then(() => self.clients.claim()));
});
self.addEventListener("message", (event) => {
  if (event.data?.type !== "cache-assets" || !Array.isArray(event.data.files)) return;
  const files = event.data.files.filter((p) => typeof p === "string"
    && (p.startsWith("/assets/") || p === "/favicon.svg"));
  event.waitUntil(caches.open(CACHE).then(async (cache) => {
    await Promise.all(files.map(async (path) => {
      if (!(await cache.match(path))) await cache.add(path).catch(() => {});
    }));
  }));
});
self.addEventListener("fetch", (event) => {
  const request = event.request;
  if (request.method !== "GET") return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;
  if (url.pathname.startsWith("/v1/") || url.pathname.startsWith("/pay/")
    || url.pathname.startsWith("/dev/") || url.pathname.startsWith("/app/")
    || url.pathname.startsWith("/ops/")) return;
  if (request.mode === "navigate" && url.pathname.startsWith("/merchant")) {
    event.respondWith(fetch(request).then((response) => {
      if (response.ok && response.type === "basic") {
        const copy = response.clone(); void caches.open(CACHE).then((cache) => cache.put("/merchant", copy));
      }
      return response;
    }).catch(async () => await caches.match("/merchant") || Response.error()));
  } else if (url.pathname.startsWith("/assets/") || url.pathname === "/favicon.svg") {
    event.respondWith(caches.match(request).then((hit) => hit || fetch(request).then((response) => {
      if (response.ok && response.type === "basic") {
        const copy = response.clone(); void caches.open(CACHE).then((cache) => cache.put(request, copy));
      }
      return response;
    })));
  }
});
