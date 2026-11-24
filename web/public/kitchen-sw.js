// Cache only the public kitchen shell and immutable built assets, never API or authenticated responses.
const CACHE = "elepay-kitchen-v3";
const assetPath = p => /^\/assets\/[A-Za-z0-9_.-]+\.(?:js|css|woff2|svg|png|webp)$/.test(p) || p === "/favicon.svg";
const publicRequest = p => new Request(p, {credentials:"omit",cache:"no-cache",redirect:"error"});
const safeResponse = res => res.ok && !res.redirected && res.type === "basic";
self.addEventListener("install", event => {
  event.waitUntil(caches.open(CACHE).then(async c=>{const res=await fetch(publicRequest("/kitchen"));if(!safeResponse(res)||!res.headers.get("Content-Type")?.includes("text/html"))throw Error("Kitchen shell unavailable");await c.put("/kitchen",res);await self.skipWaiting();}));
});
self.addEventListener("activate", event => {
  event.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(k=>k.startsWith("elepay-kitchen-")&&k!==CACHE).map(k=>caches.delete(k)))).then(()=>self.clients.claim()));
});
self.addEventListener("message", event=>{
  const files=event.data?.type==="cache"&&Array.isArray(event.data.files)?event.data.files:[];
  const safe=files.filter(f=>typeof f==="string"&&assetPath(f)).slice(0,100);
  event.waitUntil(caches.open(CACHE).then(c=>Promise.all(safe.map(async f=>{if(await c.match(f))return;try{const res=await fetch(publicRequest(f));if(safeResponse(res))await c.put(f,res);}catch{}}))));
});
self.addEventListener("fetch",event=>{
  const req=event.request,url=new URL(req.url);
  if(req.method!=="GET"||url.origin!==self.location.origin||req.headers.has("Authorization")||req.headers.has("X-Elepay-Api-Key"))return;
  if(req.mode==="navigate"&&(url.pathname==="/kitchen"||url.pathname.startsWith("/kitchen/"))&&!url.search){
    event.respondWith(fetch(publicRequest("/kitchen")).then(async res=>{if(safeResponse(res)&&res.headers.get("Content-Type")?.includes("text/html")){const c=await caches.open(CACHE);await c.put("/kitchen",res.clone());}return res;}).catch(async()=>await caches.match("/kitchen")||Response.error()));return;
  }
  if(assetPath(url.pathname)&&!url.search){event.respondWith(caches.match(url.pathname).then(async hit=>{if(hit)return hit;const res=await fetch(publicRequest(url.pathname));if(safeResponse(res)){const c=await caches.open(CACHE);await c.put(url.pathname,res.clone());}return res;}));}
});
