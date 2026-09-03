/* AMBIENT service worker — cache the shell, network-first for data */
const CACHE="ambient-v13";
const SHELL=["./","./index.html","./companion.html","./manifest.json","./icon.png"];

self.addEventListener("install",e=>{
  e.waitUntil(caches.open(CACHE).then(c=>c.addAll(SHELL))
    .then(()=>self.skipWaiting()));
});
self.addEventListener("activate",e=>{
  e.waitUntil(caches.keys().then(keys=>Promise.all(
    keys.filter(k=>k!==CACHE).map(k=>caches.delete(k))))
    .then(()=>self.clients.claim()));
});
self.addEventListener("fetch",e=>{
  const url=new URL(e.request.url);
  /* shell: cache-first; everything else (APIs): network, no SW caching —
     the app layer does its own last-known-good persistence */
  if(url.origin===location.origin){
    e.respondWith(caches.match(e.request,{ignoreSearch:true})
      .then(hit=>hit||fetch(e.request)));
  }
});
