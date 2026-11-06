const C='mynotes-202611060308';const F=['./','./index.html','./manifest.webmanifest','./icon-192.png','./icon-512.png','./icon-maskable-512.png','./apple-touch-icon.png'];
self.addEventListener('install',e=>{e.waitUntil(caches.open(C).then(c=>Promise.all(F.map(u=>fetch(new Request(u,{cache:'reload'})).then(r=>{if(r.ok)return c.put(u,r)}).catch(()=>{})))));self.skipWaiting()});
self.addEventListener('activate',e=>{e.waitUntil(caches.keys().then(ks=>Promise.all(ks.filter(k=>k!==C).map(k=>caches.delete(k)))).then(()=>self.clients.claim()))});
const page=r=>r.mode==='navigate'||/\/(index\.html)?$/.test(new URL(r.url).pathname)||/\.webmanifest$/.test(new URL(r.url).pathname);
self.addEventListener('fetch',e=>{const r=e.request;if(r.method!=='GET'||new URL(r.url).origin!==location.origin)return;
 if(page(r)){e.respondWith(new Promise(res=>{let done=false;const fb=()=>caches.match(r,{ignoreSearch:true}).then(h=>h||caches.match('./')).then(h=>{if(!done){done=true;res(h||fetch(r))}});
  const t=setTimeout(fb,4000);fetch(r,{cache:'no-store'}).then(n=>{clearTimeout(t);if(n&&n.ok){const cp=n.clone();caches.open(C).then(c=>c.put(r,cp))}if(!done){done=true;res(n)}}).catch(()=>{clearTimeout(t);fb()})}));return}
 e.respondWith(caches.match(r,{ignoreSearch:true}).then(h=>h||fetch(r).then(n=>{if(n&&n.ok){const cp=n.clone();caches.open(C).then(c=>c.put(r,cp))}return n})))});
