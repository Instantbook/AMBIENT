/* AMBIENT service worker — cache the shell, network-first for data */
const CACHE="ambient-v56";
const SHELL=["./","./index.html","./companion.html","./manifest.json","./icon.png"];

/* cache:"reload" is load-bearing, not a belt-and-braces flourish.
   addAll() fetches through the HTTP cache, and GitHub Pages serves the shell
   with max-age=600 - so a freshly installed worker would dutifully fetch
   index.html, get the PREVIOUS deploy back out of the HTTP cache, and store
   that under the new cache name. The version constant moved and the content
   did not, which is exactly the "deploys take ten minutes to reach the
   device, and restarting does not help" symptom: every reload re-primed the
   same stale window. This forces the network and refreshes the HTTP cache
   with it.

   SHELL stays atomic - addAll rejects entirely if any one URL 404s, which
   kills the install - so every path here must exist at the deployed URL. */
self.addEventListener("install",e=>{
  e.waitUntil(caches.open(CACHE)
    .then(c=>c.addAll(SHELL.map(u=>new Request(u,{cache:"reload"}))))
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
