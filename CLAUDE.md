# CLAUDE.md

Guida di riferimento per questo repository. Leggerla prima di aggiungere pagine, endpoint o dipendenze: le scelte
sono vincoli deliberati per mantenere il progetto snello. Package radice: `org.dual.replicate`. Java 21, Maven.

## Scopo

L'app serve a tre cose (single-user: `Generation` non ha owner, solo multi-conversazione):

### 1. Generare immagini con l'ausilio di un chatbot

- `/deep-chat`: l'assistente puo' cercare sul web (`WebSearchTool`, SearXNG) e generare su Replicate
  (`ImageGenerationTool`) SEMPRE col modello scelto nel combobox UI (`ImageGenerationTool.MODEL_CONTEXT_KEY` via
  `ToolContext`, non un parametro scelto dall'LLM). `/generations/new` e' la via diretta (form, senza chatbot).
- `ImageGenerationTool` avvia e torna subito; il polling continua in background (`DeepChatGenerationWatcher`, `@Async`)
  e il risultato arriva come nuovo turno di chat via SSE (`GET /events`, `GenerationEventBroadcaster`): nessun polling
  client-side per la chat.
- **Placeholder** mentre una generazione e' in corso (chat e `/generations/{id}`): `fragments/generation-placeholder.html`
  (immagine dummy + "Interrompi", stili INLINE perche' finisce anche nello shadow DOM di `<deep-chat>`). Il bottone chiede
  conferma e fa `POST /generations/{id}/cancel` (`GenerationService#cancel`): esito `FAILED` "annullata"; se il cancel
  fallisce il bottone si disabilita e si attende la fine naturale. In chat i placeholder viaggiano nella risposta del
  turno (`generationIds`) e al reload si ripristinano via `Generation.conversationId` (V11). A fine generazione il
  risultato (`fragments/generation-result.html`, stili inline, toggle `button :: galleryToggle`, handler `gen-toggle` in
  `deep-chat.html`) rimpiazza il placeholder nello stesso messaggio `html`; la cronologia ricaricata usa lo stesso markup.
- `/generations/{id}` (form diretto) fa polling htmx ogni 2s finche' non e' terminale; a stato terminale quella stessa
  pagina (`fragments/generation.html :: status`) e' anche l'UNICO dettaglio: prompt/modello/seed/parametri, tutte le
  immagini in griglia con lightbox (`fragments/generation-images.html`) cancellabili singolarmente, cancellazione
  dell'intera generazione.
- **Video (img2video/text2video)**: stessa pipeline. `Generation.kind` (`GenerationKind` IMAGE/VIDEO, V12) deriva da
  `GenerationFormType#kind()`; unico modello video `prunaai/p-video` (`P_VIDEO`, `PVideoParameterHandler`). L'mp4 sta in
  `imageFilenames`; cambiano solo rendering (`<video>`, niente lightbox) e timeout (15 min invece di 5, `GenerationService`).
  - Ingresso: icona overlay "Anima" (`button.html :: animateOverlay`) su OGNI thumbnail (`/gallery`, galleria di chat,
    griglia dettaglio) → `/generations/new?source={id}&sourceImage={filename}` (la sorgente e' quel file preciso; filename
    non della generazione → sorgente ignorata). Preseleziona p-video, hidden `sourceGenerationId`+`sourceImage`;
    `GenerationController#create` la invia come data-URI (`IImageStorageService#readAsDataUri`,
    `Generation.sourceGenerationId`, FK `ON DELETE SET NULL`). Senza sorgente p-video e' text-to-video.
  - **Upload stand-alone**: link "Genera video" (`/generations/new?kind=video`); il fragment p-video ha
    `<input type=file name=sourceUpload>` (form `hx-encoding=multipart`). `IImageStorageService#storeUpload` valida magic
    bytes (png/jpeg/webp, max 10 MB), salva con un nome nuovo (NON una `Generation`), lo traccia in
    `Generation.sourceUploadFilename` (V14); ha precedenza sulla sorgente "Anima"; eliminato con la generazione (o se la
    creazione fallisce).
  - `/deep-chat` propone SOLO modelli immagine (`ReplicateModelCatalog#models(GenerationKind)`). `disable_safety_checker`
    forzato solo per le immagini. Fuori scope: audio-to-video, video in chat.
- **Modifica immagine (flux-kontext-dev)**: modello *edit* (`GenerationFormType#isEdit`, `FLUX_KONTEXT_DEV`, V15) che
  produce un'IMMAGINE (kind IMAGE, pipeline invariata). Pagina propria `/generations/new?kind=edit` (link header); NON
  compare nel combobox immagini ne' in `/deep-chat` (`ReplicateModelCatalog#models(kind)` esclude gli edit,
  `#editModels()` li elenca). Sorgente OBBLIGATORIA, sotto `GenerationFormType#sourceImageParam()` (`input_image`;
  p-video usa `image`): upload stand-alone (`sourceUpload`, fragment `generation-params-source-upload.html`) o overlay
  "Modifica immagine" (`button.html :: editOverlay`, `?kind=edit&source=..&sourceImage=..`); senza sorgente
  `GenerationService#create` fallisce prima di chiamare Replicate. "AI enhance" usa
  `PromptEnhancementService#enhanceEdit` (visione sulla sorgente, guida `generateForm.edit-prompt-enhancement-guide`;
  serve una bozza). Un'immagine modificata e' una normale immagine (ri-modificabile/animabile).
- **LoRA al volo (flux-dev-lora)**: `black-forest-labs/flux-dev-lora` (`GenerationFormType#FLUX_DEV_LORA`, V20,
  `FluxDevLoraParameterHandler`) e' un normale modello IMAGE (compare nel combobox e in `/deep-chat`) con `lora_weights`/
  `extra_lora` (+ scale: Replicate `owner/nome`, URL HuggingFace/CivitAI o `.safetensors`; vuoti = FLUX dev puro) e
  img2img OPZIONALE da upload (`sourceUpload`, `sourceImageParam()` = `image`, + `prompt_strength`; il blocco upload e'
  nascosto nel pannello di `/deep-chat`). NON ha overlay sui thumbnail: "Anima"/"Modifica" non portano a questo modello.
  **Token** `hf_api_token`/`civitai_api_token` (campi password, per LoRA privati): non stanno nei default del handler, la
  select modello li esclude da `hx-include` (`hx-params`), `GenerationService.SECRET_INPUT_KEYS` li toglie dal
  `PARAMETERS_JSON` salvato (vanno solo a Replicate) e `deep-chat.html` (`sync()`) non li salva in `localStorage`:
  mantenere i tre punti allineati se si aggiunge un altro segreto.
- **Costo**: il dettaglio mostra il costo *stimato* (Replicate espone solo `metrics`). `ReplicatePricing` (statica, una
  regola per modello censito — un nuovo modello richiede anche la sua regola) lo calcola da `PredictionResponse.metrics`;
  `GenerationService#refresh` lo salva in `GENERATION.COST_USD` (V13); assente per generazioni vecchie, fallite o senza regola.

### 2. Indicizzare le immagini in un archivio

- Ogni generazione (chat o form) e' una riga `Generation`. `/gallery` = solo SUCCEEDED, paginata, cancellazione in blocco,
  si aggiorna via SSE; le card linkano a `/generations/{id}` (nessun dettaglio proprio). `/generations` = listato paginato
  di TUTTE le generazioni, selezione multipla (shift-click), cancellazione selezione/intero archivio (conferma testuale
  rinforzata, `generations-list.html`).
- Cancellare una generazione elimina i file; cancellare l'ultima immagine elimina a cascata la generazione
  (`GenerationService#deleteImage`).
- **Preferiti (star)**: ogni file (immagine/video) puo' avere la star (overlay rosa, token `favourite`,
  `button.html :: starOverlay`, `POST /generations/{id}/favourite`, `GenerationService#toggleFavourite`,
  `Generation.favouriteFilenames`, V16) su `/gallery`, galleria di chat, dettaglio. `/gallery` ha tab `?tab=all|favourites`:
  Tutte (una card per generazione) e Preferiti (una card per file, `GalleryItem`, senza checkbox/cancellazione in
  blocco). `deleteImage` toglie anche la star.

### 3. Storia delle conversazioni

- `/deep-chat` e' multi-conversazione: `ChatConversation` (V5) raggruppa i turni (`ChatMessage`, V3;
  `ChatConversationService`). Colonna sinistra fissa: bottone "Nuova conversazione" (`POST /deep-chat/new`) + rail NON
  collassabile (`accordion.html :: staticPanels`) con lista conversazioni (`conversation-list.html`, piu' recente attiva
  prima, ricarica cronologia completa, rinomina/cancella inline) e impostazioni di generazione (`generation-params.html`).
- Sotto la chat, accordion collassabile (`accordion.html :: panels`, Pines UI) con la galleria "contestuale"
  (`gallery.html :: grid` riusata) delle sole immagini di quella conversazione; `/gallery` resta indipendente.

**Perimetro**: non aggiungere feature (pagine demo, integrazioni, pattern) che non servano a generare, archiviare o
conversare sulle immagini (l'output puo' essere anche un video). Per dimostrare un pattern htmx/Alpine nuovo, aggiungerlo a
una feature vera. Le pagine demo starter e la chat di rifinitura prompt sono state rimosse; l'icona "AI enhance"
(`PromptEnhancementService`) non ne e' una riedizione: e' un'azione puntuale sulla form reale che riscrive il prompt.

## Filosofia

Hypermedia-first, non SPA: il server e' la fonte di verita' e restituisce HTML, non JSON; il client arricchisce.

- Navigazione → Spring MVC + Thymeleaf. Aggiornamenti parziali → htmx. Micro-interattivita' locale → Alpine.js.
- Componenti davvero complessi → Web Component isolato su un singolo `<div>`, mai un framework SPA.
- Theming → Tailwind (Play CDN) per l'intero sito: nessun `theme.css`, utility inline (vedi "Convenzione: theming").
- Zero build frontend (niente npm/webpack/vite/esbuild): htmx, Alpine, Tailwind da CDN in `fragments/layout.html`.

### Cosa NON introdurre senza una ragione concreta

- **Spring WebFlux come modello del server**: restare su `spring-boot-starter-webmvc` (Tomcat), mai
  `spring-boot-starter-webflux` (Boot sceglie UN application-type; mischiare richiederebbe un secondo server o la
  migrazione dell'app). Due eccezioni deliberate, solo **tipi Reactor**:
  1. lo starter Spring AI porta Reactor/WebFlux per il *client* HTTP verso gli LLM;
  2. `EventStreamController` (`GET /events`) ritorna `Flux<ServerSentEvent<?>>` (sorgente `Sinks.Many` in
     `GenerationEventBroadcaster`), supportato nativamente da spring-webmvc (`ReactiveTypeHandler`) sullo stesso
     Tomcat, al posto di un registro di `SseEmitter` a mano.
  Non sono un'apertura generale: un controller reattivo senza un bisogno concreto di streaming e' fuori scope.
- **React/Vue/Angular** come framework applicativo: duplicherebbe routing/stato del server.
- **Un build step Tailwind obbligatorio**: default = Play CDN (`mvn spring-boot:run`/`mvn test` non compilano nulla).
  Unica eccezione opt-in: profilo `tailwind` (`mvn -Ptailwind clean package`), che scarica via `curl` il binario
  *standalone* Tailwind 3.4 (niente Node, cache `target/tailwind/`, solo macOS/Linux) e compila
  `target/classes/static/css/tailwind.css` minificato; `config/TailwindAssets` rileva l'asset e `layout.html` serve
  `<link>` invece del CDN. Config in UN solo file `src/main/tailwind/tailwind.config.js` (CommonJS; il CDN lo carica come
  `/js/tailwind.config.js`, copiato via `<resources>` del pom, con uno shim `module` in `layout.html`). Il blocco
  `@layer base` e' duplicato tra `src/main/tailwind/input.css` e `<style type="text/tailwindcss">` di `layout.html`:
  tenerli allineati. Le classi devono restare stringhe letterali (la CLI scansiona staticamente): niente concatenazione.

## Stack

Spring Boot 4.x + Spring MVC; Thymeleaf + thymeleaf-layout-dialect (`layout:decorate`/`layout:fragment`); htmx e
Alpine.js via CDN; Pines UI (componenti Alpine+Tailwind da copiare, `preflight` attivo, stessa base di stile del sito);
Spring Data JPA + H2 su file; Flyway (`spring-boot-starter-flyway`, `ddl-auto: validate`); `RestClient`
(`spring-boot-starter-restclient`) verso Replicate; Spring AI (`spring-ai-starter-model-openai`, `ChatClient`, `base-url`
`https://openrouter.ai/api/v1`, richiede Boot 4.x / Spring AI 2.0.x); embedding locali ONNX (`spring-ai-starter-model-transformers`) e
`spring-ai-vector-store` per la ricerca semantica; Maven; Java 21.

## Struttura del progetto

Ricavabile dal repo; qui solo cio' che non e' ovvio.

- `controller/`: `GenerationController` (crea, polling/dettaglio, listato, cancellazioni, "AI enhance" `POST
  /generations/enhance-prompt`), `GalleryController` (solo SUCCEEDED), `DeepChatController` (route HTML `/deep-chat/*`),
  `DeepChatApiController` (JSON per `<deep-chat>`), `EventStreamController` (`GET /events`, unico push), `ImageController` (`GET /images/{file}`, unico punto da cui
  escono i binari: dallo storage, con Range per il seek dei video ed ETag), `ErrorController`.
- `domain/`: `Generation`, `ChatConversation`, `ChatMessage`, `ReplicateModel` (catalogo censito, V6),
  `GenerationFormType` (form/handler di un modello: FLUX_LORA_FF3, FLUX_2_KLEIN_9B, FLUX_KREA_DEV, P_VIDEO,
  FLUX_KONTEXT_DEV, FLUX_DEV_LORA; `kind()`, `sourceImageParam()`, `isEdit()`), `GenerationKind`.
- `replicate/`: `ReplicateClient`, `ReplicateModelCatalog`, `ReplicatePricing`, `TooManyPredictionsException` (troppe
  prediction in corso PER LO STESSO MODELLO, vedi `GenerationService#create`). `search/`: `SearxngClient` (Basic Auth).
- `service/`: `GenerationService` (crea prediction, avanza stato, download; pubblica `GenerationCompletedEvent` a ogni
  transizione terminale; `GenerationsDeletedEvent`/`GenerationImageDeletedEvent` per le cancellazioni),
  `GenerationParameterHandler` (un'implementazione per form-type, risolte da `GenerationParameterHandlers`; `image` di
  p-video lo aggiunge `GenerationController`, `input_image` di kontext `GenerationService`), `storage/`
  (vedi "Convenzione: interfacce e storage dei binari"), `PromptEnhancementService` (one-shot, senza tool ne' cronologia, `ChatClient`
  dedicato senza `defaultTools`; `enhanceVideo`/`enhanceEdit` guardano l'immagine sorgente con un modello di visione
  OpenRouter non moderato `enhancer.vision-model`/`vision-fallback-model`, guide in `prompts.properties`; un rifiuto del
  modello e' intercettato e non sovrascrive la textarea), `DeepChatService`, `DeepChatGenerationWatcher`,
  `WebSearchTool`, `ImageGenerationTool`, `GenerationResultHolder` (canale tool→`DeepChatService` via `ToolContext`: gli
  id delle generazioni avviate nel turno), `AppErrorService`, `GenerationRecoveryService`, `DeepChatFailedException`.
- `search/vector/`: `H2VectorStore` (`VectorStore` su H2, tabella `VECTOR_DOC` V19), `ArchiveIndexService` (riconciliazione dell'indice),
  `SemanticSearchConfig`; `service/ArchiveSearchTool` (tool `searchArchive` della chat). Vedi "Ricerca semantica".
- `remote/`: `RemoteServiceException`, `RemoteCaller`, `RetryPolicy`, `RestClientTranslator`, `RestRemoteClient` (vedi
  "Errori e retry generici"). `config/`: `TailwindAssets`, `UnhandledExceptionResolver`.
- `db/migration/`: V1..V20, una per modifica di schema (vedi "Convenzione: migrazioni"). Le migrazioni che aggiungono un
  modello estendono l'ENUM `FORM_TYPE` e fanno il seed in `REPLICATE_MODEL` (`VERSION NULL` = "ultima versione").
- `templates/fragments/`: `layout.html` (shell, config Tailwind, `@layer base`), `header.html` (sticky; sotto `md` link e
  theme switch in uno slideover Pines, stato Alpine `navOpen`, `button.html :: navToggle`), `button.html` (bottoni +
  overlay dei thumbnail: `animateOverlay`, `editOverlay`, `starOverlay`, `downloadOverlay`), `alert.html`,
  `generate-form.html` (`promptField` e' il blocco textarea+"AI enhance", risostituito in outerHTML da `enhance-prompt`),
  `generation-params.html` (guscio: select modello + campi del form-type, condiviso da form e chat) con un
  `generation-params-<form-type>.html` per form-type e `generation-params-source-upload.html` (`field(label, required)`),
  `generation.html`, `generation-placeholder.html`, `generation-result.html`, `generation-images.html`, `gallery.html`,
  `gallery-card.html`, `generations.html`, `generation-row.html`, `description-list.html`, `pagination.html`,
  `accordion.html`, `conversation-list.html`, `toast.html`,
  `live-events.html` (SSE `GET /events` ri-dispatchata come CustomEvent su `document.body`).

Immagini generate e DB H2 vivono in `./data/` (fuori da git). Nessun CSS in `static/`: `static/css/tailwind.css` esiste
solo se generato dal profilo `tailwind` (in `target/`, mai committato).

## Pattern Thymeleaf: layout manager

Ogni pagina si decora con `layout:decorate="~{fragments/layout}"` sul proprio `<html>` e mette il contenuto in
`<div layout:fragment="content">`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" xmlns:layout="http://www.ultraq.net.nz/thymeleaf/layout"
      layout:decorate="~{fragments/layout}">
<head>
    <title>Titolo pagina</title>
</head>
<body>
<div layout:fragment="content">
    <!-- contenuto -->
</div>
</body>
</html>
```

- Niente `lang` letterale sul proprio `<html>`: lo risolve il decoratore; ripeterlo lo vince nel merge e disattiva il
  meccanismo (vedi i18n).
- `<title>` della pagina sostituisce quello del layout automaticamente; il resto di `<head>` viene *fuso*.
- Un fragment della pagina sostituisce l'elemento del decoratore, non solo il contenuto: `content` e' un `<div>`, NON un
  `<main>` e NON porta le classi del contenitore. L'unico `<main>` (con `content` e `breadcrumbs` opzionale) vive in
  `layout.html`; ripeterlo annida `<main>` e raddoppia il padding.

## Pattern controller: fragment vs pagina intera

**Stessa URL, due risposte**, distinte dall'header `HX-Request`:

```java
@GetMapping("/{id}")
public String status(@PathVariable Long id,
                      @RequestHeader(value = "HX-Request", required = false) String hxRequest,
                      Model model) {
    // ... popolare il model ...
    boolean isHtmxRequest = "true".equalsIgnoreCase(hxRequest);
    return isHtmxRequest ? "fragments/generation :: status" : "generation-status";
}
```

Un fragment con parametri restituito come vista di risposta diretta richiede parametri **nominati**
(`frag(nome=${valore})`); la forma posizionale funziona solo in un `th:replace` dentro un altro template, altrimenti 500
(`Parameters in a view specification must be named`). Esempi: `GenerationController` (fragment senza parametri),
`GalleryController` (paginazione, `hx-target="#gallery-content"` + `hx-swap="innerHTML"`, parametri nominati).

## Convenzione: attributi che portano un URL dell'app

Ogni attributo con un URL dell'app (`href`, `src`, `action`, `hx-get/post/put/delete`, URL passati a Web Component come
`connect` di `<deep-chat>`) passa **sempre** da `@{...}`, anche se il path e' letterale: l'app puo' stare dietro un
reverse proxy su subpath (`server.forward-headers-strategy`) e solo `@{...}` applica `X-Forwarded-Prefix`. Bug reale gia'
capitato (vedi `generate-form.html`, `deep-chat.html`).

- Per `hx-*` basta il prefisso `th:` con `@{...}` dentro:
  ```html
  <form th:hx-post="@{/generations}" hx-target="#generation-panel" hx-swap="innerHTML"
        th:action="@{/generations}" method="post">
  <a th:hx-get="@{/gallery(page=${p})}" hx-target="#gallery-content" hx-swap="innerHTML">...</a>
  ```
- Fuori da htmx (es. `connect`, letto via JS): `th:attr` con `@{...}` dentro una literal substitution `|...|`
  (vedi `deep-chat.html`).
- **Lato Java**: `request.getContextPath() + "/gallery"`, mai `"/gallery"` letterale (`ForwardedHeaderFilter` include il
  prefisso). Es. `GenerationController#delete`/`#deleteImage` per l'header `HX-Redirect`. `redirect:"..."` come nome di
  vista non ne ha bisogno.

## Convenzione: theming

Nessun file CSS: solo Tailwind, config inline in `fragments/layout.html`. Mai colori hardcoded fuori da
`theme.extend.colors`.

- **Token**: un blocco `{ DEFAULT, dark }` per colore (`canvas: { DEFAULT: '#ffffff', dark: '#0d1117' }`), usato come
  `bg-canvas dark:bg-canvas-dark` (il suffisso `-dark` e' solo una shade in piu'; il prefisso `dark:` decide quando usarla).
  Nomi: `canvas`/`surface`/`ink`/`ink-muted`/`line`/`accent`/`accent-contrast`/`danger` (+ `favourite`).
- **Dark mode**: `darkMode: ['selector', '[data-theme="dark"]']`, non `prefers-color-scheme`. Il toggle light/dark/auto
  (Alpine su `<body>` + localStorage/`prefers-color-scheme`) scrive `data-theme`. Lo script di boot inline in `<head>`
  (prima di Tailwind) risolve `auto` e setta `data-theme` PRIMA della compilazione delle classi (evita il FOUC): non
  spostarlo piu' in basso.
- **`@layer base`** (in `layout.html`): solo per elementi "nudi" identici in piu' punti (link, `<code>`, form controls
  senza wrapper, `[x-cloak]`). Specificita' bassa: le classi inline vincono sempre. Tutto il resto e' utility inline, mai
  una nuova regola `@layer`.
- **Bottoni**: mai `<button>` a mano; usare i fragment di `fragments/button.html` (`primary`/`danger`/`themeToggle`/...),
  sempre con parametri nominati e passando TUTTI i parametri dichiarati (gli inutilizzati a `null`, es. `hxPost` su `danger`
  se htmx sta sul `<form>`). Se nessuna variante calza, aggiungere un fragment li'.
- **Binding Alpine** (`@click`, `:class`, `:title`, `:placeholder`...) non passano da `th:attr` ne' da `#{...}`: il
  valore dinamico si porta in un attributo `data-*` renderizzato da Thymeleaf (`th:data-theme-value="${value}"`) e si
  legge a runtime con `$el.dataset.themeValue`, lasciando l'espressione Alpine HTML statico. Vale anche per le stringhe
  i18n client-only (vedi toggle impostazioni in `deep-chat.html`).
- **Limite**: le varianti Tailwind gestiscono solo due stati per colore (default + `dark:`); un terzo tema (es.
  "high-contrast") richiederebbe di ripensare `theme.extend.colors`, non e' un costo fisso.

## Convenzione: interfacce e storage dei binari

- **Interfacce**: il nome inizia SEMPRE con `I` (`IImageStorageService`); le implementazioni no e dicono il backend
  (`LocalFsImageStorageService`, `WebDavImageStorageService`). Vale per ogni nuova interfaccia.
- **Storage dei binari** (`service/storage/`): tutto cio' che l'app serve come file (immagini, mp4, upload sorgente)
  passa da `IImageStorageService`; nessun accesso diretto al filesystem/WebDAV altrove e nessun resource handler statico:
  `/images/**` lo serve `ImageController` leggendo dallo storage. Un nuovo tipo di binario si aggiunge li', non a parte.
  Logica comune (download, magic bytes, nomi, confinamento del filename) in `AbstractImageStorageService`; i backend
  implementano solo `write`/`remove`/`size`/`openRange`. Backend scelto da `storage.type` (`local` default | `webdav`),
  alternativi (passando a WebDAV i file locali esistenti non sono raggiungibili finche' non si esegue la migrazione).
- **Nomi dei file**: OGNI binario nuovo (output di una generazione, upload) si chiama `<sha256 di 32 byte casuali,
  hex>.<ext>` (`AbstractImageStorageService#newFilename`), mai derivato da id, URL o nome originale: niente collisioni
  nemmeno dopo un reset del DB e nessuna informazione sul contenuto. NON e' un hash del contenuto (nessuna dedup: una riga
  = un file, cancellare non tocca le altre). L'estensione e' solo un'indicazione (su WebDAV il file e' cifrato). Il
  filename e' opaco per l'app: i file storici (`<id>-<n>.<ext>`, `upload-<uuid>.<ext>`) restano validi, nessuna migrazione.
- **Layout fisico annidato** (local e WebDAV): il filename resta piatto (DB, URL `/images/{file}`), ma sul backend vive in
  `ab/cd/<filename>` con `abcd` = primi 2 byte hex dello SHA-256 del FILENAME (`AbstractImageStorageService#shardPath`:
  derivabile dal solo filename, nessuna colonna in piu'). Su WebDAV le collezioni `ab` e `ab/cd` si creano con MKCOL alla
  prima scrittura. Nessuna migrazione dei file preesistenti: i vecchi file piatti non sono piu' serviti.
- **WebDAV**: contenuti SEMPRE cifrati (AES-256-GCM a chunk da 64 KiB, `ChunkedAesGcmCipher`: autenticato, Range/seek
  senza decifrare tutto) con la chiave base64 `storage.webdav.encryption-key` (env `STORAGE_WEBDAV_ENCRYPTION_KEY`, mai nel
  repo; avvio fallisce se manca/non e' 32 byte; persa la chiave i binari sono irrecuperabili). Solo i contenuti sono
  cifrati, i nomi file no. Client = `RestClient.Builder` iniettato (PUT su `.part` + MOVE, GET con Range, HEAD, DELETE,
  MKCOL), nessuna libreria WebDAV.
- **Migrazione locale → WebDAV** (`LocalToWebDavMigrator`): una tantum, opt-in con
  `storage.migration.from-local.enabled=true` + `storage.type=webdav`; parte all'avvio (`ApplicationReadyEvent`) sui file
  di `storage.images-dir` (esclusi i `.part`), salta quelli gia' sul server (HEAD: riavviabile, idempotente), un file che
  fallisce non ferma gli altri (`AppErrorService`, `migrateLocalToWebDav`). I locali restano, salvo
  `delete-local=true`: ognuno si elimina solo se la dimensione in chiaro riportata dal SERVER coincide. Finche' non ha
  finito, i file non migrati non sono serviti; a fine giro rimettere `enabled=false`.
- **Cache locale** (`EncryptedBlobCache`, `storage.webdav.cache.*`, default 2 GB in `./data/cache`, `0` = off): tiene i
  blob CIFRATI (mai il chiaro), write-through alla scrittura e read-through su miss, eviction LRU, blob oltre il tetto
  letti a range direttamente da WebDAV. Nomi immutabili e unici: nessuna invalidazione se non su `delete`. Un errore di
  cache non fa fallire la richiesta (registrato con `AppErrorSource.STORAGE`).

## Convenzione: migrazioni database (Flyway)

`ddl-auto: validate`: Hibernate controlla solo che lo schema Flyway corrisponda alle entity (altrimenti l'app non parte).
Ogni modifica alla persistenza (entity, campo, indice, rename...) richiede una **nuova migrazione SQL**
`V<N+1>__<descrizione>.sql` in `src/main/resources/db/migration/` (DDL H2; mai riusare un numero gia' applicato ne'
modificare un file gia' eseguito: il checksum fa fallire l'avvio). Flyway le applica all'avvio prima della validazione.
In sviluppo si puo' ripartire da zero cancellando `./data/db/`.

## Convenzione: internazionalizzazione (i18n)

Tutto il testo utente-visibile (template e messaggi d'errore Java) passa da `MessageSource` + `#{...}`, mai stringhe
hardcoded. La lingua segue `Accept-Language` (`AcceptHeaderLocaleResolver`, default Boot: nessun bean da scrivere, niente
switcher/cookie/sessione). Bundle: `messages.properties` (italiano, default/fallback anche per locale non mappate) e
`messages_en.properties`. Nuova lingua: nuovo `messages_<locale>.properties` con le stesse chiavi e aggiornare
`TemplateRenderingTests.messageBundlesHaveMatchingKeys`. Chiavi punto-separate `<pagina-o-componente>.<categoria>.<elemento>`
(`header.nav.home`, `replicate.error.tokenMissing`); bundle piatto unico per template ed errori Java.

- **Apostrofi (bug silenziosi)**: Spring usa `MessageFormat` SOLO con argomenti non nulli. Senza parametri
  (`#{key}`, `Messages.get(code)`) gli apostrofi restano letterali (`l'app`); con parametri (`#{key(${arg})}`,
  `Messages.get(code, args...)`) vanno raddoppiati (`''`) o spariscono. Argomenti numerici (id, durate) passano per
  `NumberFormat` con separatori di migliaia: usare `{0,number,#}`, non `{0}`.
- **Lato Java**: iniettare `org.dual.replicate.i18n.Messages` (wrapper su `MessageSourceAccessor`, locale della richiesta
  via `LocaleContextHolder`) ovunque un errore possa arrivare all'utente (oggi `ReplicateClient`, `SearxngClient`,
  `GenerationService`, `IImageStorageService`, `GenerationController`, `DeepChatApiController`, `DeepChatService`).
  Risolvere al call site, prima di costruire l'eccezione, mai nel costruttore. Se la classe ha gia' una variabile
  `messages` (es. `DeepChatService`), chiamare il campo iniettato altrimenti (li' `i18n`).
- **`<html lang>`** viene dal bundle (`html.lang=it|en`, `th:lang="#{html.lang}"` sul decoratore), non da
  `#{#locale.language}`: su una locale non mappata il contenuto e' comunque italiano.
- **Limiti accettati**: `Generation.errorMessage` e' salvato gia' tradotto nella locale di chi ha generato l'errore (resta
  congelato); il catch-all di `DeepChatApiController` traduce solo il prefisso `"Errore nel contattare l'assistente: "`,
  non i messaggi di eccezioni di librerie terze.

## Convenzione: errori delle chiamate remote e stati terminali

Ogni chiamata a Replicate, OpenRouter (Spring AI) o SearXNG, e ogni errore interno non gestito, passa da
`AppErrorService#record(operation, throwable[, generationId, conversationId])` (la source si ricava da
`RemoteServiceException#source()`; la forma `record(source, ...)` resta per gli errori non remoti, `INTERNAL`): MAI un `catch`
che ingoia o soltanto logga. `record` logga con stack, salva/aggiorna una riga `APP_ERROR` (V17, transazione propria, non lancia mai;
consultabile da `/errors`, `ErrorController`) e alla prima occorrenza di una *serie* pubblica `ErrorToastEvent` → SSE
`error-toast` → toast in tutte le tab con `live-events.html`. Serie = stesso (source, operation, generationId, tipo
eccezione) entro 5 minuti: aggiorna `occurrences`/`last_seen_at` invece di creare riga/toast a ogni poll durante un outage.

- **Toast** (`fragments/toast.html`, incluso da `layout.html`): ascolta l'evento window `app-error` ({key, message}),
  dedupe per `key`. Sorgenti: SSE; header `HX-Trigger` (`AppErrorService#addToastHeader`, usato da `GenerationController`
  create/enhance/cancel e `UnhandledExceptionResolver`); listener globali `htmx:responseError`/`htmx:sendError` (solo se
  la risposta non portava gia' un toast).
- **Chi registra**: dove l'eccezione e' *gestita/ingoiata* (servizi in background, tool, watcher); se risale a un
  controller la registra il controller (`create`, `enhancePrompt`). `UnhandledExceptionResolver` (LOWEST_PRECEDENCE) e
  `AsyncErrorConfig` sono la rete per il resto.
- **Nessuno stato indefinito**, tre reti: (a) `GenerationService#refresh` non lascia stati parziali: download/
  post-processing in try/catch → `FAILED` + file ripuliti; un errore di poll *transitorio* (`ReplicateException#isTransient`:
  rete, timeout, 429/5xx) NON fallisce la generazione ma il timeout di business vale comunque e annulla la prediction; uno
  *permanente* (4xx) la fallisce subito. (b) `GenerationRecoveryService` all'avvio (`ApplicationReadyEvent`) fa avanzare
  ogni PENDING/PROCESSING e riavvia i watcher persi. (c) lo stesso servizio, ogni `app.recovery.sweep-interval`, chiude le
  righe oltre timeout e scrive i turni di chat mancanti (`DeepChatGenerationWatcher#persistOutcome`, idempotente).
  Disattivabile con `app.recovery.enabled=false` (i test).
- **Cancellare o far scadere** una generazione in corso annulla la prediction (`cancelPredictionQuietly`); se `create`
  non riesce a salvare la riga dopo aver creato la prediction, la annulla.
- **Chat**: se l'LLM fallisce, `DeepChatService#reply` scrive un turno ASSISTANT d'errore (`ChatMessage.error`, V18: in
  rosso, mai rimandato all'LLM) e lancia `DeepChatFailedException` (gia' registrata: `DeepChatApiController` mostra solo il
  messaggio). I tool (`WebSearchTool`, `ImageGenerationTool`) catturano da soli e rimandano il testo d'errore al modello.
- **WebDAV** (`WebDavImageStorageService`, via `RemoteCaller` come gli altri): PUT/MOVE/DELETE/MKCOL e gli HEAD della
  migrazione ritentano (`RetryPolicy.DEFAULT`) i soli transitori; le letture per `/images/**` NO (`RetryPolicy.NONE`: il
  browser riprova, un retry allungherebbe la richiesta). `NoSuchFileException` (404) e' `passThrough`, non un errore. Un `.part`
  che non si riesce a ripulire e' registrato (`cleanupPart`); un file che non si riesce a cancellare resta orfano (registrato
  `deleteFile`, nessun recupero automatico). `IImageStorageService` lancia SOLO `StorageException` (mai `UncheckedIOException`):
  `REJECTED` per gli esiti attesi (file inesistente, upload troppo grande/di tipo non valido).
- **Timeout**: `spring.http.clients.connect-timeout/read-timeout` valgono per tutti i `RestClient.Builder`
  auto-configurati (Replicate, download, Spring AI); SearXNG ha un timeout piu' stretto proprio. Un nuovo client HTTP
  usa il `RestClient.Builder` iniettato, mai `RestClient.create()`.
- **Locale**: `AppErrorService` risolve il toast con la locale del thread; un thread async la imposta prima
  (`DeepChatGenerationWatcher#watch`), il recupero usa l'italiano.

### Errori e retry generici (`remote/`) e checklist "nuovo servizio remoto"

Un solo tipo, un solo esecutore, una sola traduzione HTTP:

- **`RemoteServiceException`** (radice di `ReplicateException`, `SearxngException`, `StorageException`, `OpenRouterException`)
  porta `source()` e `kind()`: `TRANSIENT` (rete/timeout/408/429/5xx, ritentabile), `PERMANENT` (4xx, 507, risposta illeggibile),
  `CONFIGURATION` (token/credenziali mancanti), `REJECTED` (rifiuto applicativo ATTESO: validazione, "non trovato", rifiuto del
  modello). `isReportable()` = tutto tranne `REJECTED`: solo i reportable si registrano/notificano (`GenerationController#create`
  ne decide cosi' "toast o solo form"); il resolver risponde 502 (guasto di un servizio esterno), 422 (`REJECTED`, senza riga
  in `APP_ERROR`, con un toast htmx del solo messaggio) o 500 (bug interno). `ReplicateException(String)` = `REJECTED`; con
  causa = `PERMANENT`: un errore vero senza causa va costruito con `Kind` esplicito.
- **`RemoteCaller#call(operazione[, RetryPolicy], supplier)`**: traduce qualunque eccezione e ritenta solo i `TRANSIENT`.
  `RetryPolicy` sempre esplicita per le operazioni NON idempotenti/a pagamento: `RetryPolicy.NONE` (es. `createPrediction`: un
  ritentativo potrebbe fatturare una seconda prediction). Un errore gia' classificato non viene ritradotto anche se RestClient lo
  ha incapsulato in una `ResourceAccessException`. OpenRouter/Spring AI ritenta gia' da se' (`spring.ai.retry.*`, a livello HTTP,
  prima dei tool): `OpenRouterException.CALLER` traduce senza un secondo strato di retry (rieseguire un turno rieseguirebbe i tool).
- **`RestClientTranslator`**: unica regola stato HTTP -> `Kind`; messaggi da `<prefix>.error.httpError|connectionFailed`.

Per aggiungere un servizio remoto:
1. Valore in `AppErrorSource` + `errors.source.<X>` nei due bundle.
2. `class FooException extends RemoteServiceException` (costruttore `(String message, Throwable cause, Kind kind)`).
3. Client `extends RestRemoteClient` con prefisso `foo` (chiavi `foo.error.httpError|connectionFailed` nei bundle), e ogni
   chiamata in `remote.call("operazione", () -> ...)` (`RetryPolicy.NONE` se non idempotente). Esempio minimo:
   `RestRemoteClientTest`.
4. Chiamante in background: `appErrors.record("operazione", e, ...)`; controller: `appErrors.recordForHtmx(response, "operazione", e)`
   oppure lasciar risalire (il resolver registra con la source giusta). Niente `catch` che ingoia.
- **Front end**: nessun codice per servizio. Il toast (`fragments/toast.html`) e' guidato dal payload `{key, message,
  transient}`; `transient: true` aggiunge "Riprova tra qualche istante". `AppErrorService#addHxTrigger` FONDE gli eventi
  nell'unico header `HX-Trigger` (un controller puo' emettere `gallery-update` e un toast insieme). Un bottone "Riprova" generico
  sul toast e' escluso di proposito: rieseguire una POST (`create`) creerebbe una seconda prediction a pagamento.

## Ricerca semantica (vector store su H2, embedding locali)

Nessun DB o servizio esterno. Chi vuole cercare per significato dipende SOLO dall'interfaccia Spring AI `VectorStore` (bean
`H2VectorStore`): un domani si puo' sostituire con Elasticsearch/Qdrant cambiando quel bean.

- **Embedding**: `EmbeddingModel` locale (`TransformersEmbeddingModel`, ONNX) con `multilingual-e5-small` quantizzato (384 dim,
  italiano/inglese, ~120 MB). Il modello e il tokenizer si scaricano UNA volta al primo avvio da Hugging Face in `./data/models`
  (fuori da git; niente download in build). `spring.ai.model.embedding=transformers` evita che l'autoconfig OpenAI crei un secondo
  `EmbeddingModel`. I modelli e5 vogliono i prefissi `passage: ` (documenti) e `query: ` (ricerche): li applica `H2VectorStore`,
  chi lo usa passa il testo nudo. I punteggi e5 sono compressi (0.7-0.9): usare top-K, non soglie fisse.
- **`H2VectorStore`**: documenti in `VECTOR_DOC` (embedding normalizzato, `EMBEDDING_MODEL`, `CONTENT_HASH`), letti da una mappa in
  memoria (coseno = prodotto scalare, lineare: adatto a decine di migliaia di righe). Ogni documento ha i metadata `type`
  (stringa) e `refId` (numero), opzionali `conversationId`, `role`, `kind`. Filtri supportati: EQ, NE, IN, NIN, AND, OR, NOT,
  ISNULL/ISNOTNULL. Cambiare modello (URI ONNX = id del modello) => alla riconciliazione successiva si ri-embedda tutto.
- **`ArchiveIndexService`** allinea l'indice con una riconciliazione idempotente (non ganci su ogni `save`): prompt delle generazioni
  SUCCEEDED (`type=generation`), messaggi di chat non d'errore (`chat`), titoli (`conversation`); aggiunge i mancanti/cambiati,
  rimuove i documenti la cui riga non esiste piu'. Gira in background all'avvio (backfill), ogni `app.search.reindex-interval` e a
  ogni `GenerationCompletedEvent`. Un documento che fallisce e' registrato (`AppErrorService`) e non ferma gli altri.
- **`ArchiveSearchTool`** (`searchArchive(query, type?)`) e' tra i tool di `DeepChatService` solo se `app.search.enabled`.
- **Link alle generazioni in chat**: `searchArchive` restituisce al modello path assoluti (`/generations/12`). Dietro un reverse
  proxy su subpath non funzionerebbero, quindi `deep-chat.html` li riscrive SOLO in visualizzazione (`linkGenerations`, su
  `responseInterceptor` e sulla cronologia) in link markdown RELATIVI alla pagina corrente (`/deep-chat` -> `generations/12`,
  `/deep-chat/5` -> `../generations/12`), senza dipendere da `X-Forwarded-Prefix`. Il testo salvato resta l'originale.
- `app.search.enabled=false` (i test, `application-test.yml`) spegne indice, tool ed `EmbeddingModel` (`spring.ai.model.embedding=none`):
  `mvn test` non scarica ne' carica mai il modello. I test usano un embedding finto (`FakeEmbeddingModel`). Prove reali, opt-in:
  `mvn test -Dtest='E5ModelSmokeTest,SemanticSearchWiringTest' -Dsemantic.model.test=true`.
- **UI `/search`** (`SemanticSearchController`, `search.html` + `fragments/search.html`; link nell'header solo con `app.search.enabled`):
  interroga (`GET /search/results`, punteggi in %, via l'interfaccia `VectorStore`), sfoglia per tipo (`/search/list/{type}`: il tipo sta
  nel PATH cosi' la paginazione generica non lo perde), mostra dettagli/metadata/modello/hash, statistiche e "Riconcilia ora".
  **Solo le note manuali (`type=note`) sono creabili/modificabili/eliminabili**: la riconciliazione non le tocca. I documenti
  derivati (generation/chat/conversation) sono in sola lettura (la fonte di verita' e' il DB, una modifica o cancellazione a mano
  verrebbe annullata al giro dopo): su di essi solo "Ri-embedda" (anche dopo un cambio di modello). Un id non-nota su
  modifica/eliminazione => 422.
  "Nuova nota" e "Modifica" usano lo STESSO **dialog modale Pines** (`search.html`, stessa meccanica del lightbox:
  `x-data="{ dialogOpen: false }"`, `x-trap.inert.noscroll`, senza teleport; bottoni `button :: dialogOpen|dialogClose|dialogCloseIcon`,
  che assumono `dialogOpen` su un antenato). `dialogOpen` con `hxGet` ricarica `#note-form` (`fragments/search :: noteForm`, vuoto per
  `GET /search/notes/new`, precompilato per `/search/notes/{id}/edit`) a ogni apertura. Al salvataggio riuscito il server emette
  `HX-Trigger: note-saved` (`AppErrorService#addHxTrigger`) che chiude il dialog (risposta: elenco in creazione, riga `#doc-...`
  in modifica); con un errore di validazione risponde col form (`HX-Retarget: #note-form`) e il dialog resta aperto. Le statistiche
  si aggiornano fuori banda (`hx-swap-oob`).
  La ricerca ha una **soglia di somiglianza minima** in % (`threshold`, 0..100, default `app.search.similarity-threshold-percent`=0):
  i punteggi e5 sono compressi (tipicamente 70-90%), quindi la soglia utile e' alta.
  Test del controller con embedding finto: `SemanticSearchControllerTest`.
- Fuori scope per ora: ricerca semantica nelle liste/galleria esistenti, descrizioni delle immagini con un modello di visione.

## Comandi utili

Nessun Maven Wrapper (serve Maven installato; `mvn wrapper:wrapper` per generarlo).

```bash
mvn spring-boot:run          # sviluppo (Thymeleaf cache=false)
mvn test                     # test
mvn clean package            # jar eseguibile (Tailwind via Play CDN)
mvn -Ptailwind clean package # + CSS Tailwind compilato/minificato (richiede rete per il binario)
```

`mvn test` non tocca mai `./data/db/`: Surefire attiva il profilo Spring "test" (`<systemPropertyVariables>` in
`pom.xml`, non un'annotazione per classe) che sposta il datasource su H2 in-memory (`src/test/resources/application-test.yml`).
Prima i `@SpringBootTest` scrivevano `Generation` di prova nel DB di sviluppo.
Stesso principio per lo storage: `spring.config.import` carica il `.env` reale anche sotto Surefire, quindi `pom.xml` fissa
come proprieta' di sistema `storage.type=local` e `storage.migration.from-local.enabled=false` (battono qualunque file);
un test che vuole WebDAV o la migrazione li sovrascrive con `@SpringBootTest(properties=...)`, mai contro il server vero.

## Checklist per una nuova pagina/feature

1. Solo navigazione → nuovo controller + template col pattern layout manager.
2. Aggiornamento parziale (ricerca live, paginazione, form senza reload) → estrarre un fragment in
   `fragments/<nome>.html`; il controller lo restituisce se `HX-Request`, la pagina intera altrimenti.
3. Solo interattivita' locale → Alpine (`x-data`/`x-show`/`x-on`) nel template, senza controller.
4. Componente complesso stateful → valutare prima un Web Component isolato.
5. Tocca un'entity JPA → nuova migrazione Flyway, mai `ddl-auto`.
6. Testo utente-visibile → chiave in entrambi i bundle, mai stringa hardcoded (template o eccezione).
7. Serve un `<button>` → fragment di `button.html` (aggiungerne uno se nessuno calza), mai inline.
