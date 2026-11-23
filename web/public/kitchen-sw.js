// Kitchen screen service worker: lets a tablet reopen /kitchen after a power cut even before the internet is back.
// Orders themselves are never cached here; the screen keeps its own copy and syncs when the network returns.
const CACHE = "elepay-kitchen-v2";

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(CACHE).then((c) => c.add("/kitchen")).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (event) => {
  event.waitUntil(caches.keys()
    .then((keys) => Promise.all(keys.filter((k) => k.startsWith("elepay-kitchen-") && k !== CACHE).map((k) => caches.delete(k))))
    .then(() => self.clients.claim()));
});

// The page lists the app files it loaded before this worker took control; keep copies of them.
self.addEventListener("message", (event) => {
  const files = event.data && event.data.type === "cache" && Array.isArray(event.data.files) ? event.data.files : [];
  const safe = files.filter((f) => typeof f === "string" && (f.startsWith("/assets/") || f === "/favicon.svg"));
  event.waitUntil(caches.open(CACHE).then((c) => Promise.all(safe.map((f) =>
    c.match(f).then((hit) => hit || c.add(f).catch(() => {}))))));
});

self.addEventListener("fetch", (event) => {
  const req = event.request;
  if (req.method !== "GET") return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin || url.pathname.startsWith("/v1/") || url.pathname.startsWith("/dev/")) return;

  if (req.mode === "navigate") {
    // Fresh page when online; the cached one when not.
    event.respondWith(fetch(req).then((res) => {
      if (res.ok) caches.open(CACHE).then((c) => c.put("/kitchen", res.clone()));
      return res;
    }).catch(() => caches.match("/kitchen")));
    return;
  }
  if (url.pathname.startsWith("/assets/") || url.pathname === "/favicon.svg") {
    // Built assets have content hashes in their names, so a cached copy is always correct.
    event.respondWith(caches.match(req).then((hit) => hit || fetch(req).then((res) => {
      if (res.ok) { const copy = res.clone(); caches.open(CACHE).then((c) => c.put(req, copy)); }
      return res;
    })));
  }
});
