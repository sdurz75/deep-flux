/*
 * Service worker di hexa-pwa. Obiettivo: app installabile e shell offline, NIENTE di piu'.
 * Mai in cache: HTML dinamico (la stessa URL ha due risposte, pagina intera e fragment htmx, distinte da HX-Request), non-GET, SSE (/events),
 * binari (/images/**, Range + ETag). Il server resta la fonte di verita'; offline si vede solo la pagina /offline.
 * __SCOPE__, __OFFLINE_URL__ e __CACHE_NAME__ li sostituisce PwaService.
 */
'use strict';

const SCOPE = '__SCOPE__';
const OFFLINE_URL = '__OFFLINE_URL__';
const CACHE = '__CACHE_NAME__';
// Librerie del layout caricate da CDN (htmx, Alpine, Tailwind Play): senza copia locale offline la pagina sarebbe senza stile ne' script.
const CDN_HOSTS = ['unpkg.com', 'cdn.tailwindcss.com'];
// Asset statici dell'app serviti dalla stessa origine (relativi allo scope, che puo' essere un sottopercorso).
const STATIC_PREFIXES = ['js/', 'css/', 'pwa/'];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE)
      .then((cache) => cache.add(new Request(OFFLINE_URL, { cache: 'reload' })))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key))))
      .then(() => self.clients.claim())
  );
});

function isStaticAsset(url) {
  if (url.origin !== self.location.origin || !url.pathname.startsWith(SCOPE)) {
    return false;
  }
  const relative = url.pathname.substring(SCOPE.length);
  return STATIC_PREFIXES.some((prefix) => relative.startsWith(prefix));
}

function isCdn(url) {
  return CDN_HOSTS.includes(url.hostname);
}

// Stale-while-revalidate: risponde dalla cache se c'e', intanto aggiorna. Le risposte opache dei CDN (no-cors) si memorizzano comunque.
function staleWhileRevalidate(request) {
  return caches.open(CACHE).then((cache) =>
    cache.match(request).then((cached) => {
      const network = fetch(request)
        .then((response) => {
          if (response && (response.ok || response.type === 'opaque')) {
            cache.put(request, response.clone());
          }
          return response;
        })
        .catch((error) => {
          if (cached) {
            return cached;
          }
          throw error;
        });
      return cached || network;
    })
  );
}

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET' || request.headers.has('HX-Request')) {
    return; // passthrough: POST/htmx/SSE non passano dal worker
  }
  const url = new URL(request.url);

  if (request.mode === 'navigate') {
    if (url.origin !== self.location.origin) {
      return;
    }
    event.respondWith(
      fetch(request).catch(() => caches.match(OFFLINE_URL).then((offline) => offline || Response.error()))
    );
    return;
  }

  if (isStaticAsset(url) || isCdn(url)) {
    event.respondWith(staleWhileRevalidate(request));
  }
  // tutto il resto (compresi /events e /images/**): passthrough
});
