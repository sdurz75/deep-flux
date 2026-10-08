# App installabile (hexa-pwa)

Questa pagina riguarda le app generate con `-DusePwa=true` (dipendenza `hexa-pwa`). Basta la dipendenza: nessun file da importare, nessun codice. L'app diventa installabile (computer e telefono) e, senza rete, mostra una pagina che lo spiega.

## Che cosa aggiunge

- `/manifest.webmanifest`: nome e nome breve da `app.title` e `app.brand` del vostro bundle (quindi per lingua), `start_url` e `scope` sotto il context path, icone.
- `/sw.js`: il service worker, servito dalla radice dell'app (così il suo scope la copre tutta) e sempre `no-cache`.
- `/offline`: la pagina mostrata senza rete, col layout del core. Il testo sta nel bundle `messages-pwa` della libreria.
- Nel `<head>` di ogni pagina: il collegamento al manifest, `theme-color` (chiaro e scuro), l'icona e la registrazione del worker. Il layout del core non conosce la libreria: inserisce i fragment elencati nel model attribute `headFragments`, che `hexa-pwa` riempie.

## Che cosa tiene in cache il worker

Poco, di proposito: la pagina `/offline`, gli asset statici (`js/`, `css/`, `pwa/`) e gli script dei CDN usati dal layout (htmx, Alpine, Tailwind), con la strategia «rispondi dalla cache e intanto aggiorna». Non mette **mai** in cache l'HTML dinamico (la stessa URL risponde con la pagina intera o con un fragment, a seconda di `HX-Request`), le richieste non GET, gli eventi in tempo reale (`/events`) e i binari (`/images/**`). Il nome della cache dipende dalla build: ogni release invalida la precedente. Offline funziona davvero solo dopo una visita online, perché i CDN si copiano a runtime.

## Personalizzare

- **Icone**: file con gli stessi nomi in `src/main/resources/static/pwa/icons/` (`icon-192.png`, `icon-512.png`, `icon-maskable-512.png`, `apple-touch-icon.png`, `icon.svg`); vincono su quelli della libreria.
- **Colori**: `app.pwa.theme-color` e `app.pwa.theme-color-dark` (esadecimali, perché manifest e `meta` non accettano classi Tailwind), `app.pwa.display` (default `standalone`).
- **Spegnere**: `app.pwa.enabled=false` o `HX_PWA_ENABLED=false` (default acceso; la property prevale).

## Con il blocco con PIN

Se l'utente attiva il [blocco con PIN](16-blocco-con-pin.md) il browser deve poter ancora leggere manifest, service worker, pagina offline e icone: `hexa-pwa` li dichiara al blocco con `ILockExemptPaths`, quindi restano raggiungibili anche a sessione bloccata. Tutto il resto, e quindi anche le pagine, passa dal PIN come sempre; un redirect a `/unlock` non finisce mai in cache.

## Requisiti

I service worker funzionano solo su HTTPS (`localhost` è esente). Dietro un reverse proxy su sottopercorso scope e `start_url` seguono il context path, come ogni altro URL dell'app.

## Testare

Il test `theAppIsInstallable` (in `PagesRenderingTests`) verifica head, manifest e intestazioni del worker. L'installazione vera e la prova offline si fanno nel browser: Chrome, DevTools, scheda Application, «Manifest» e «Service workers», poi Network → Offline e ricarica.
