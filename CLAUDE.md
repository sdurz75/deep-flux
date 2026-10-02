# CLAUDE.md

Guida di riferimento per questo repository. Leggerla prima di aggiungere pagine, endpoint o dipendenze: le scelte
sono vincoli deliberati per mantenere il progetto snello. Package radice: `org.dual.replicate`. Java 21, Maven, UN solo modulo.

Il codice e' diviso in un **`core`** generico e riusabile (layout/fragments, remote+retry, eventi di sistema, push SSE, secrets/token,
storage dei binari) e un'**`app`** specifica (generazione immagini, galleria, chat, ricerca semantica), entrambi organizzati in esagoni
(ports & adapters) uno per sottosistema: vedi "Architettura". Il repo e' pensato anche come template per una nuova webapp (si tiene il
`core`, si sostituisce `app`): vedi `docs/TEMPLATE.md`.

## Scopo

L'app serve a tre cose (single-user: `Generation` non ha owner, solo multi-conversazione):

### 1. Generare immagini con l'ausilio di un chatbot

- `/deep-chat`: l'assistente puo' cercare sul web (`WebSearchTool`, SearXNG) e generare su Replicate
  (`ImageGenerationTool`) SEMPRE col modello scelto nel combobox UI (`ImageGenerationTool.MODEL_CONTEXT_KEY` via
  `ToolContext`, non un parametro scelto dall'LLM; solo `SpringAiAssistant` costruisce il `ToolContext`). `/generations/new` e'
  la via diretta (form, senza chatbot).
- `ImageGenerationTool` avvia e torna subito; il polling continua in background (`ChatGenerationWatcher`, `@Async`)
  e il risultato arriva come nuovo turno di chat via SSE (`GET /events`, `EventStreamController`, `IClientPush`; il lato chat e'
  `ChatPushNotifier`): nessun polling client-side per la chat.
- **Placeholder** mentre una generazione e' in corso (chat e `/generations/{id}`): `fragments/app/generation-placeholder.html`
  (immagine dummy + "Interrompi", stili INLINE perche' finisce anche nello shadow DOM di `<deep-chat>`). Il bottone chiede
  conferma e fa `POST /generations/{id}/cancel` (`IGenerations#cancel`): esito `FAILED` "annullata"; se il cancel
  fallisce il bottone si disabilita e si attende la fine naturale. In chat i placeholder viaggiano nella risposta del
  turno (`generationIds`) e al reload si ripristinano via `Generation.conversationId`. A fine generazione il
  risultato (`fragments/app/generation-result.html`, stili inline, toggle `button-gen :: galleryToggle`, handler `gen-toggle` in
  `templates/app/deep-chat.html`) rimpiazza il placeholder nello stesso messaggio `html`; la cronologia ricaricata usa lo stesso markup.
- `/generations/{id}` (form diretto) fa polling htmx ogni 2s finche' non e' terminale; a stato terminale quella stessa
  pagina (`fragments/app/generation.html :: status`) e' anche l'UNICO dettaglio: prompt/modello/seed/parametri, tutte le
  immagini in griglia con lightbox (`fragments/app/generation-images.html`) cancellabili singolarmente, cancellazione
  dell'intera generazione.
- **Video (img2video/text2video)**: stessa pipeline. `Generation.kind` (`GenerationKind` IMAGE/VIDEO) deriva da
  `GenerationFormType#kind()`; unico modello video `prunaai/p-video` (`P_VIDEO`, `PVideoParameterHandler`). L'mp4 sta in
  `imageFilenames`; cambiano solo rendering (`<video>`, niente lightbox) e timeout (15 min invece di 5, `GenerationService`).
  - Ingresso: icona overlay "Anima" (`button-gen :: animateOverlay`) su OGNI thumbnail (`/gallery`, galleria di chat,
    griglia dettaglio) → `/generations/new?source={id}&sourceImage={filename}` (la sorgente e' quel file preciso; filename
    non della generazione → sorgente ignorata). Preseleziona p-video, hidden `sourceGenerationId`+`sourceImage`;
    `GenerationController#create` la invia come data-URI (`IImageStorageService#readAsDataUri`,
    `Generation.sourceGenerationId`, FK `ON DELETE SET NULL`). Senza sorgente p-video e' text-to-video.
  - **Upload stand-alone**: link "Genera video" (`/generations/new?kind=video`); il fragment p-video ha
    `<input type=file name=sourceUpload>` (form `hx-encoding=multipart`). `IImageStorageService#storeUpload` valida magic
    bytes (png/jpeg/webp, max `IImageStorageService.MAX_UPLOAD_BYTES` = 10 MB), salva con un nome nuovo (NON una `Generation`), lo traccia in
    `Generation.sourceUploadFilename`; ha precedenza sulla sorgente "Anima"; eliminato con la generazione (o se la
    creazione fallisce). Il controller costruisce un `UploadedFile` (tipo di dominio dello storage): la porta non vede `MultipartFile`.
  - `/deep-chat` propone SOLO modelli immagine (`IModelCatalog#models(GenerationKind)`). `disable_safety_checker`
    forzato solo per le immagini. Fuori scope: audio-to-video, video in chat.
- **Modifica immagine (flux-kontext-dev)**: modello *edit* (`GenerationFormType#isEdit`, `FLUX_KONTEXT_DEV`) che
  produce un'IMMAGINE (kind IMAGE, pipeline invariata). Pagina propria `/generations/new?kind=edit` (link header); NON
  compare nel combobox immagini ne' in `/deep-chat` (`IModelCatalog#models(kind)` esclude gli edit,
  `#editModels()` li elenca). Sorgente OBBLIGATORIA, sotto `GenerationFormType#sourceImageParam()` (`input_image`;
  p-video usa `image`): upload stand-alone (`sourceUpload`, fragment `generation-params-source-upload.html`) o overlay
  "Modifica immagine" (`button-gen :: editOverlay`, `?kind=edit&source=..&sourceImage=..`); senza sorgente
  `GenerationService#create` fallisce prima di chiamare Replicate. "AI enhance" usa
  `IPromptEnhancer#enhanceEdit` (visione sulla sorgente, guida `generateForm.edit-prompt-enhancement-guide` in `prompts.properties`;
  serve una bozza). Un'immagine modificata e' una normale immagine (ri-modificabile/animabile).
- **LoRA al volo (flux-dev-lora)**: `black-forest-labs/flux-dev-lora` (`GenerationFormType#FLUX_DEV_LORA`,
  `FluxDevLoraParameterHandler`) e' un normale modello IMAGE (compare nel combobox e in `/deep-chat`) con `lora_weights`/
  `extra_lora` (+ scale: Replicate `owner/nome`, URL HuggingFace/CivitAI o `.safetensors`; vuoti = FLUX dev puro) e
  img2img OPZIONALE da upload (`sourceUpload`, `sourceImageParam()` = `image`, + `prompt_strength`; il blocco upload e'
  nascosto nel pannello di `/deep-chat`). NON ha overlay sui thumbnail: "Anima"/"Modifica" non portano a questo modello.
  **Token** per i LoRA privati: NON si digitano nel form ma si scelgono PER NOME (select) fra quelli salvati in `/tokens`
  (vedi "Token API"). Al server arriva l'ID (`hf_token_id`/`civitai_token_id`, in `PARAMETERS_JSON` resta l'ID): il token in
  chiaro esiste solo in `GenerationService#doCreate`, che con `TokenInputResolver#resolveInto` (generation.application, sopra
  `IApiTokens#resolve`) lo mette in `hf_api_token`/`civitai_api_token` dell'input per Replicate, PRIMA di chiamarlo (un token
  inesistente o scaduto e' un rifiuto, nessuna prediction).
  **LoRA anagrafati**: CRUD in `/loras` (`LoraController`, `ILoraPresets`/`LoraPresetService`, `LoraPreset`, `fragments/app/loras.html`,
  voce nel menu Sistema), solo per comodita': nome, sorgente, intensita' predefinita, trigger words, nota. Sopra i due slot LoRA
  della form (`generation-params-flux-dev-lora.html`, `loraPresets` nel Model dove si mettono gia' i token) una select Pines SENZA
  `name` compila testo e scala (restano modificabili, "testo libero" non tocca nulla) e mostra le trigger words con "Aggiungi al prompt"
  (`button-gen :: addToPrompt`, solo se c'e' `#prompt`: non nel pannello di `/deep-chat`). E' un aiuto lato client: al server arrivano
  sempre testo e scala, nessuna FK dalla `Generation`, cancellare/modificare un preset non tocca le generazioni passate.
- **Costo**: il dettaglio mostra il costo *stimato* (Replicate espone solo `metrics`). `ReplicatePricing` (statica, in
  `generation.domain`, una regola per modello censito — un nuovo modello richiede anche la sua regola) lo calcola da
  `PredictionResponse.metrics`; `IGenerations#refresh` lo salva in `generation.cost_usd`; assente per generazioni vecchie, fallite o senza regola.

### 2. Indicizzare le immagini in un archivio

- Ogni generazione (chat o form) e' una riga `Generation`. `/gallery` = solo SUCCEEDED, paginata, cancellazione in blocco,
  si aggiorna via SSE (`GalleryPushNotifier`, evento `gallery-update`); le card linkano a `/generations/{id}` (nessun dettaglio proprio).
  `/generations` = listato paginato di TUTTE le generazioni, selezione multipla (shift-click), cancellazione selezione/intero
  archivio (conferma testuale rinforzata, `fragments/app/generations.html`).
- Cancellare una generazione elimina i file; cancellare l'ultima immagine elimina a cascata la generazione
  (`IGenerations#deleteImage`).
- **Preferiti (star)**: ogni file (immagine/video) puo' avere la star (overlay rosa, token `favourite`,
  `button-gen :: starOverlay`, `POST /generations/{id}/favourite`, `IGenerations#toggleFavourite`,
  `Generation.favouriteFilenames`) su `/gallery`, galleria di chat, dettaglio. `/gallery` ha tab `?tab=all|favourites`:
  Tutte (una card per generazione) e Preferiti (una card per file, `GalleryItem`, senza checkbox/cancellazione in
  blocco). `deleteImage` toglie anche la star.

### 3. Storia delle conversazioni

- `/deep-chat` e' multi-conversazione: `ChatConversation` raggruppa i turni (`ChatMessage`; porta `IChatConversations`,
  `ChatConversationService`). Colonna sinistra fissa: bottone "Nuova conversazione" (`POST /deep-chat/new`) + rail NON
  collassabile (`fragments/app/accordion.html :: staticPanels`) con lista conversazioni (`fragments/app/conversation-list.html`,
  piu' recente attiva prima, ricarica cronologia completa, rinomina/cancella inline) e impostazioni di generazione
  (`fragments/app/generation-params.html`).
- Sotto la chat, accordion collassabile (`fragments/app/accordion.html :: panels`, Pines UI) con la galleria "contestuale"
  (`fragments/app/gallery.html :: grid` riusata) con TUTTI i file (immagini e video) di quella conversazione, una card per file
  (`IGenerations#succeededItemsForConversation`, `GalleryItem.allOf`), non solo il primo di ogni generazione. La selezione e'
  PER FILE (`selectionByFile`: checkbox `files` = `<idGenerazione>:<filename>`, `POST /gallery/delete-selected-files`,
  `IGenerations#deleteImages`: una generazione che perde tutti i file e' eliminata a cascata); in `/gallery` resta una card e una
  selezione per generazione. `/gallery` resta indipendente.
- La chat conosce una generazione solo per id (`ChatMessage.generationId`, FK `ON DELETE SET NULL`): per gli allegati della
  cronologia legge `IGenerations#findAllById` in blocco.

**Perimetro**: non aggiungere feature (pagine demo, integrazioni, pattern) che non servano a generare, archiviare o
conversare sulle immagini (l'output puo' essere anche un video). Per dimostrare un pattern htmx/Alpine nuovo, aggiungerlo a
una feature vera. Le pagine demo starter e la chat di rifinitura prompt sono state rimosse; l'icona "AI enhance"
(`IPromptEnhancer`) non ne e' una riedizione: e' un'azione puntuale sulla form reale che riscrive il prompt.

## Filosofia

Hypermedia-first, non SPA: il server e' la fonte di verita' e restituisce HTML, non JSON; il client arricchisce.

- Navigazione → Spring MVC + Thymeleaf. Aggiornamenti parziali → htmx. Micro-interattivita' locale → Alpine.js.
- Componenti davvero complessi → Web Component isolato su un singolo `<div>`, mai un framework SPA.
- Theming → Tailwind (Play CDN) per l'intero sito: nessun `theme.css`, utility inline (vedi "Convenzione: theming").
- Zero build frontend (niente npm/webpack/vite/esbuild): htmx, Alpine, Tailwind da CDN in `fragments/core/layout.html`.

### Cosa NON introdurre senza una ragione concreta

- **Spring WebFlux come modello del server**: restare su `spring-boot-starter-webmvc` (Tomcat), mai
  `spring-boot-starter-webflux` (Boot sceglie UN application-type; mischiare richiederebbe un secondo server o la
  migrazione dell'app). Due eccezioni deliberate, solo **tipi Reactor**:
  1. lo starter Spring AI porta Reactor/WebFlux per il *client* HTTP verso gli LLM;
  2. `EventStreamController` (`GET /events`) ritorna `Flux<ServerSentEvent<?>>` (sorgente `Sinks.Many` in
     `PushService`, esposto dalla porta `IClientPushStream`), supportato nativamente da spring-webmvc (`ReactiveTypeHandler`) sullo
     stesso Tomcat, al posto di un registro di `SseEmitter` a mano. E' l'unica whitelist Reactor delle porte (vedi `ArchitectureTest`).
  Non sono un'apertura generale: un controller reattivo senza un bisogno concreto di streaming e' fuori scope.
- **React/Vue/Angular** come framework applicativo: duplicherebbe routing/stato del server.
- **Un build step Tailwind obbligatorio**: default = Play CDN (`mvn spring-boot:run`/`mvn test` non compilano nulla).
  Unica eccezione opt-in: profilo `tailwind` (`mvn -Ptailwind clean package`), che scarica via `curl` il binario
  *standalone* Tailwind 3.4 (niente Node, cache `target/tailwind/`, solo macOS/Linux) e compila
  `target/classes/static/css/tailwind.css` minificato; `core.web.TailwindAssets` (bean `tailwindAssets`) rileva l'asset e
  `fragments/core/layout.html` serve `<link>` invece del CDN. Config in UN solo file `src/main/tailwind/tailwind.config.js` (CommonJS;
  il CDN lo carica come `/js/tailwind.config.js`, copiato via `<resources>` del pom, con uno shim `module` in `layout.html`). Il blocco
  `@layer base` e' duplicato tra `src/main/tailwind/input.css` e `<style type="text/tailwindcss">` di `layout.html`:
  tenerli allineati. Le classi devono restare stringhe letterali (la CLI scansiona staticamente): niente concatenazione.

## Stack

Spring Boot 4.x + Spring MVC; Thymeleaf + thymeleaf-layout-dialect (`layout:decorate`/`layout:fragment`); htmx e
Alpine.js via CDN; Pines UI (componenti Alpine+Tailwind da copiare, `preflight` attivo, stessa base di stile del sito);
Spring Data JPA + PostgreSQL con l'estensione pgvector (un'istanza sola per dati e vettori; `compose.yaml` per lo sviluppo); Flyway (`spring-boot-starter-flyway`, `ddl-auto: validate`); `RestClient`
(`spring-boot-starter-restclient`) verso Replicate; Spring AI (`spring-ai-starter-model-openai`, `ChatClient`, `base-url`
`https://openrouter.ai/api/v1`, richiede Boot 4.x / Spring AI 2.0.x); embedding locali ONNX (`spring-ai-starter-model-transformers`) e
`spring-ai-vector-store` + `spring-ai-pgvector-store` (`PgVectorStore`) per la ricerca semantica; ArchUnit (`archunit-junit5`, solo scope test) per far
rispettare l'architettura; Maven; Java 21.

## Architettura (core/app, esagoni)

Ogni sottosistema e' un **esagono** (ports & adapters *pragmatico*) e sta o in `org.dual.replicate.core.<sottosistema>` (generico,
riusabile da ogni webapp costruita su questo template) o in `org.dual.replicate.app.<sottosistema>` (specifico di questa app).
Dipendenza solo `app → core`, mai il contrario. `Application` (root) e `support` (solo test: `PostgresTestContainerInitializer`) sono
le uniche classi fuori da `core`/`app`.

```
<core|app>/<sottosistema>/
  domain/            entity JPA, value object, enum, eccezioni, eventi di dominio, regole pure (es. ReplicatePricing)
  application/       use case: implementano le porte in (<Capability>Service), @Transactional; usano SOLO port.out e altre port.in
  port/in/           interfacce I<Capability>: l'API del sottosistema, per gli adapter in e per gli ALTRI sottosistemi
  port/out/          interfacce I<Thing>Store | Gateway | Backend | Notifier implementate dagli adapter out
  adapter/in/<tech>/ web (controller), scheduling, async, ai... (chi pilota il sottosistema)
  adapter/out/<tech>/ persistence (Spring Data + Jpa<..>Store), replicate/searxng/http/webdav/push/search... (cio' che il sottosistema usa)
```

- **Naming**: porta in `IGenerations` → impl `GenerationService`; porta out `IGenerationStore` → impl `JpaGenerationStore`. I repository
  Spring Data sono **package-private** nell'adapter `persistence` (un controller o un use case non vede mai un repository).
  Le interfacce iniziano SEMPRE con `I`, le implementazioni no (vedi anche "Convenzione: interfacce").
- **Cosa e' ammesso dove** (le regole sono nei test, vedi sotto): `domain` e' JDK + `jakarta.persistence` + `org.hibernate.annotations/type`
  (per `@JdbcTypeCode(SqlTypes.LONGVARCHAR)`) + altro `domain` + kernel; le entity JPA e le annotazioni Spring (`@Service`,
  `@Transactional`, `@ConditionalOnProperty`...) sono ammesse in `domain`/`application`. `application` NON usa `adapter..`, ne'
  `org.springframework.web|http|ai|data`, `jakarta.servlet`, `java.net.http`. `port..` dipende solo da `domain`, altre `port` e
  kernel (unica whitelist: Reactor in `IClientPushStream`). `adapter.in` non dipende da `adapter.out` e viceversa. L'adapter
  `adapter.ai` (ne' `in` ne' `out`: ChatClient + tool, bidirezionale) e' l'unico nome che sfugge a quest'ultima regola.
- **Fra sottosistemi** (anche core↔app e feature↔feature) si dipende SOLO da `port.in` e `domain` dell'altro, mai da `application` o
  `adapter`. Eccezioni: il **kernel** (`core.kernel`: `RemoteServiceException`/`RemoteCaller`/`RetryPolicy`, `Messages`, `EventSource`,
  `Paged`, `ToastMessage`, `ChunkedAesGcmCipher`) e il **kit UI** (`core.web`: `HtmxEvents`, `PaginationSupport`, `TailwindAssets`) sono
  condivisi, non esagoni. Una classe nel package radice `app` (`OpenRouterCalls`, `AppStartupOrder`) non appartiene a nessuna slice
  ed e' condivisa fra feature. Il kernel non dipende da nessun sottosistema. Nessun ciclo fra sottosistemi.
- **Punti di estensione**: un'implementazione dell'app puo' implementare una `port.out` del core: `IEventLinkResolver` (`AppEventLinks`),
  `ITokenProviderCatalog` (`AppTokenProviders`); `EventSource` (kernel) e' implementata da `CoreEventSource` e `AppEventSource`.
- **Grafo delle feature dell'app**: `prompt` e `search` sono foglie; `generation` → `prompt`, `search`; `chat` → `generation`, `search`;
  `generation` NON conosce `chat` (solo `Generation.conversationId`, un `Long`). `search` NON conosce `generation` ne' `chat`: legge i
  loro dati tramite la SPI `ISearchableSource` (in `search.port.in`), implementata da `GenerationSearchSource` (generation,
  `adapter.out.search`: ascolta anche `GenerationCompletedEvent` e chiama `IArchiveIndex#reindexAsync`) e da `ChatSearchSource` (chat).
  `app.shared` (`AppEventSource`, `AppEventSubjects`, `OpenRouterException`, `HomeController`, `AppEventLinks`) e' il dominio comune dell'app.
- **`ArchitectureTest`** (`src/test/.../architecture`, ArchUnit, `DoNotIncludeTests`, 12 regole `@ArchTest`): `domainStaysPure`,
  `applicationDoesNotTouchInfrastructure`, `portsDependOnlyOnDomain`, `drivingAdaptersDoNotUseDrivenAdapters`,
  `drivenAdaptersDoNotUseDrivingAdapters`, `coreDoesNotKnowApp`, `coreDoesNotUseLegacyLayerPackages`, `kernelDependsOnNoSubsystem`,
  `subsystemsOnlyUseEachOthersPortsIn`, `coreSubsystemsHaveNoCycles`, `appFeaturesHaveNoCycles` e la regola di **chiusura**
  `nothingOutsideCoreAndApp` (ATTIVA: nessuna classe fuori da `core..`, `app..`, `support..` e `Application`: niente package per layer
  `controller/service/repository...`). Le regole ammettono package vuoti (`allowEmptyShould`). Una violazione si corregge nel codice,
  non allentando la regola.

### Dove sta cosa

| Sottosistema | Responsabilita' | Porte principali |
|---|---|---|
| `core.kernel` (+ `.remote`, `.i18n`, `.crypto`) | errori/retry remoti, `Messages`, cifratura a chunk, `Paged`, `EventSource` | (shared kernel, niente porte) |
| `core.web` | kit UI: `HtmxEvents` (header `HX-Trigger`/toast), `PaginationSupport`, `TailwindAssets` | (shared, niente porte) |
| `core.events` | registro eventi di sistema (errori/avvisi), campanella, pagina `/system/events`, toast, `UnhandledExceptionResolver` | in `ISystemEvents`; out `ISystemEventStore`, `IToastNotifier`, `IEventLinkResolver` (app) |
| `core.push` | SSE verso le tab (`GET /events`), `PushService` (`Sinks`) | in `IClientPush`, `IClientPushStream` |
| `core.secrets` | cifratura dei segreti a riposo | in `ISecretCipher` |
| `core.tokens` | CRUD token API cifrati, scadenze, `/tokens` | in `IApiTokens`; out `IApiTokenStore`, `ITokenProviderCatalog` (app) |
| `core.storage` | binari (immagini/mp4/upload): nome, validazione, local/WebDAV, `/images/{file}`, migrazione | in `IImageStorageService`; out `IBlobBackend`, `IBlobImportTarget`, `IRemoteFileFetcher` |
| `app.generation` | generazioni (immagini/video/edit), catalogo modelli, form-type, LoRA, galleria, costo, Replicate, recupero | in `IGenerations`, `IModelCatalog`, `IGenerationForms`, `ILoraPresets`; out `IGenerationStore`, `IModelStore`, `ILoraPresetStore`, `IPredictionGateway` |
| `app.chat` | `/deep-chat`: conversazioni, turni, assistente (LLM + tool), watcher delle generazioni, recupero | in `IChat`, `IChatConversations`, `IChatRecovery`; out `IAssistant`, `IChatConversationStore`, `IChatMessageStore`, `IChatNotifier`, `IWebSearchGateway` |
| `app.search` | ricerca semantica, indice (riconciliazione), note, `/search` | in `IArchiveSearch`, `IArchiveNotes`, `IArchiveIndex`, `ISearchableSource` (SPI); out `IVectorIndex` |
| `app.prompt` | "AI enhance" del prompt (one-shot) | in `IPromptEnhancer`; out `IPromptModel` |
| `app.shared` | `AppEventSource`, `AppEventSubjects`, `OpenRouterException`, `HomeController`, `AppEventLinks` | (dominio comune dell'app) |

### Checklist: aggiungere un sottosistema o una feature

1. Core o app? Generico (riusabile da un'altra webapp) → `core.<nome>`; altrimenti `app.<nome>`. Il core non puo' dipendere dall'app.
2. Creare solo i package che servono (`domain`, `application`, `port.in`, `port.out`, `adapter.in.<tech>`, `adapter.out.<tech>`); la porta `I<Capability>`
   e' l'unica cosa che gli altri sottosistemi vedono. Disegnare PRIMA le porte (senza tipi web/HTTP/Spring Data/Spring AI: usare tipi di dominio o
   `core.kernel.Paged`), poi gli adapter.
3. Persistenza: entity in `domain`, `Spring Data` package-private + `Jpa<..>Store` in `adapter.out.persistence`, migrazione Flyway nella location giusta
   (vedi "Convenzione: migrazioni"); nessuna FK dal core all'app.
4. Dipendere da un altro sottosistema solo via la sua `port.in`/`domain`. Se serve un dato di un altro sottosistema senza che questo ti conosca,
   definire una SPI nella tua `port.in` (come `ISearchableSource`) che lui implementa in un `adapter.out.<tua-feature>`.
5. Un tipo di evento/servizio remoto nuovo: vedi "Checklist nuovo servizio remoto". Bundle i18n: chiavi nel bundle giusto (vedi "Convenzione: i18n").
6. Verificare con `mvn test`: `ArchitectureTest` deve restare verde; aggiungere i test accanto al package (stessa struttura sotto `src/test`).

## Struttura del progetto

Ricavabile dal repo (`git ls-files`); qui solo cio' che non e' ovvio. Sotto `core/<s>` e `app/<s>`: vedi "Dove sta cosa".

- `core.events`: `SystemEventService` (impl di `ISystemEvents`), `JpaSystemEventStore`, `SystemEventController` (web, `/system/events`, campanella),
  `UnhandledExceptionResolver` (rete per gli errori non gestiti), `AsyncErrorConfig` (eccezioni dei `@Async void`), `PushToastNotifier` (toast
  via `IClientPush`), domain `SystemEvent`/`SystemEventSeverity`/`CoreEventSource`/`EventLink`/`EventPage`.
- `core.push`: `PushService`, `EventStreamController` (`GET /events`, unico push), `PushModelAdvice` (espone a `live-events.html` i nomi
  `app.push.client-events`/`reconnect-events`). `core.secrets`: `SecretCipher`. `core.tokens`: `ApiTokenService`, `TokenController`,
  `TokenExpiryScheduler`, `JpaApiTokenStore`, `TokenException`.
- `core.storage`: `ImageStorageService` (impl di `IImageStorageService`), `StorageNames` (`newFilename`, `shardPath`), backend `LocalFsBlobBackend`
  e `WebDavBlobBackend` (+ `EncryptedBlobCache`), `HttpFileFetcher`, `ImageController` (`GET /images/{file}`, unico punto da cui escono i binari,
  Range per il seek dei video ed ETag), `LocalToWebDavMigrator`.
- `app.generation`: `GenerationService` (crea prediction, avanza stato, download; pubblica `GenerationCompletedEvent` a ogni transizione
  terminale; `GenerationsDeletedEvent`/`GenerationImageDeletedEvent` per le cancellazioni), `GenerationController` (crea, polling/dettaglio,
  listato, cancellazioni, "AI enhance" `POST /generations/enhance-prompt`), `GalleryController` (solo SUCCEEDED), `LoraController`,
  `adapter.in.web.form` (la conversione campi di form → parametri sta nell'interface layer, non nell'esagono): `GenerationFormRegistry`
  (impl di `IGenerationForms`, il contratto che vede la chat) + un `IGenerationParameterHandler` per form-type (`FluxLoraFf3ParameterHandler`,
  `Flux2Klein9bParameterHandler`, `FluxKreaDevParameterHandler`, `PVideoParameterHandler`, `FluxKontextDevParameterHandler`,
  `FluxDevLoraParameterHandler`; `image` di p-video e `input_image` di kontext le aggiunge `GenerationService`). L'esagono riceve la
  conversione gia' fatta: `IGenerations#create(CreateCommand)` prende una `Map<String,Object>` tipizzata (vocabolario Replicate), mai JSON o campi di form;
  `num_outputs` e' limitato a `IGenerationParameterHandler.MAX_NUM_OUTPUTS` (4, limite di Replicate, GLOBALE per ogni form-type con piu' immagini:
  ff3, krea, dev-lora) sia dal `max` dei fragment sia da `asNumOutputs` (clamp 1..4 lato server: il pannello della chat non passa da validazione HTML);
  kind, chiave della sorgente e `aspect_ratio` dei video con sorgente li decide `GenerationService` dal form-type,
  `ModelCatalogService` (impl di `IModelCatalog`, catalogo censito in `replicate_model`), `TokenInputResolver`, adapter `replicate`
  (`ReplicateClient`, `ReplicatePredictionGateway`, `PredictionResponse`), `GalleryPushNotifier`, `AppTokenProviders`,
  `GenerationRecoveryService` (adapter in scheduling). Domain: `Generation`, `GenerationKind`/`Status`/`FormType`, `ReplicateModel`,
  `LoraPreset`, `ReplicatePricing`, `ReplicateException`, `TooManyPredictionsException` (troppe prediction in corso PER LO STESSO MODELLO, vedi `GenerationService#create`),
  `ApiTokenProvider` (CIVITAI, HUGGINGFACE).
- `app.chat`: `ChatService` (un turno: persiste, chiede la risposta a `IAssistant`, avvia i watcher), `ChatConversationService`,
  `ChatGenerationWatcher` (`watch` `@Async`, `persistOutcome` idempotente; il legame generazione↔conversazione lo scrive `ChatService` con `IGenerations#attachToConversation`, sincrono, prima che la risposta del turno raggiunga il client), `ChatRecoveryService` (+
  `ChatRecoveryScheduler`), `DeepChatController` (route HTML `/deep-chat/*`), `DeepChatApiController` (JSON per `<deep-chat>`),
  `adapter.ai`: `SpringAiAssistant` (`ChatClient`), `WebSearchTool`, `ImageGenerationTool`, `ArchiveSearchTool`, `GenerationResultHolder`
  (canale tool→assistente via `ToolContext`: gli id delle generazioni avviate nel turno); `adapter.out.searxng`: `SearxngClient` (Basic Auth,
  impl di `IWebSearchGateway`), `ChatPushNotifier`, `ChatSearchSource`, store JPA. Domain: `ChatConversation`, `ChatMessage`, `FileRef`,
  `ChatTurn`, `ChatReply`, `DeepChatFailedException`, `AssistantException`.
- `app.prompt`: `PromptEnhancementService` (one-shot, senza tool ne' cronologia, `ChatClient` dedicato in `ChatClientPromptModel` senza
  `defaultTools`; `enhanceVideo`/`enhanceEdit` guardano l'immagine sorgente con un modello di visione OpenRouter non moderato
  `enhancer.vision-model`/`vision-fallback-model`, guide in `prompts.properties`; un rifiuto del modello e' intercettato e non
  sovrascrive la textarea, anche sul percorso solo-testo). Tono/contesto creativo: UNA clausola condivisa `prompts.creative-context` in
  `prompts.properties`, inclusa (`${...}`) in tutte le guide dell'enhancer e nel system prompt della chat (`SpringAiAssistant`): enhancer e chat non
  devono divergere in permissivita'; i limiti (persone reali identificabili, minori) stanno in quella clausola (`PromptGuidesTest`).
- `app.search`: vedi "Ricerca semantica". `app.shared`: vedi "Dove sta cosa". Root `app`: `OpenRouterCalls` (`RemoteCaller` condiviso per OpenRouter),
  `AppStartupOrder` (ordine dei listener di `ApplicationReadyEvent`: prima il recupero delle generazioni, poi quello della chat).
- Risorse: `application.yml` (SOLO config dell'app: Spring AI, `replicate.*`, `searxng.*`, `enhancer.*`, `app.recovery.*`, `app.search.*`,
  `app.push.*`, Flyway `locations`) che importa `core.yml` (config del core: web/MVC, i18n, DB, eventi, secrets/token, storage) e
  `prompts.properties`; le chiavi dei due file sono disgiunte (un file importato ha la precedenza su quello che lo importa). Bundle
  `messages-core(.en).properties` + `messages(.en).properties` (app). `db/migration/core` + `db/migration/app`.
- `templates/`: `fragments/core/` (`layout`, `header`, `button`, `alert`, `select`, `toast`, `notification-bell`, `live-events`, `pagination`,
  `description-list`, `system-events`, `tokens`), `fragments/app/` (tutto il resto, incl. `nav.html`, `button-gen.html`, un
  `generation-params-<form-type>.html` per form-type, `generation-params-source-upload.html`), pagine `templates/core/` (`system-events`, `tokens`) e
  `templates/app/` (`index`, `generate`, `generation-status`, `generations-list`, `gallery`, `deep-chat`, `loras`, `search`).
  `header.html` e' sticky; sotto `md` link e theme switch stanno in uno slideover Pines (stato Alpine `navOpen`, `button :: navToggle`);
  le voci di navigazione le mette l'app in `fragments/app/nav.html :: links(inline)` (punto di estensione). `layout.html` legge brand/titolo/footer dalle
  chiavi `app.brand|title|footer` del bundle dell'app. `generate-form.html`: `promptField` e' il blocco textarea+"AI enhance",
  risostituito in outerHTML da `enhance-prompt`; `generation-params.html` e' il guscio condiviso da form e chat (select modello + campi del form-type).
  `live-events.html`: SSE `GET /events` ri-dispatchata come CustomEvent su `document.body`.

Le immagini generate vivono in `./data/images` e il DB di sviluppo (container Postgres) in `./data/postgres`, entrambi fuori da git. Nessun CSS in `static/`: `static/css/tailwind.css` esiste
solo se generato dal profilo `tailwind` (in `target/`, mai committato).

## Pattern Thymeleaf: layout manager

Ogni pagina si decora con `layout:decorate="~{fragments/core/layout}"` sul proprio `<html>` e mette il contenuto in
`<div layout:fragment="content">`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" xmlns:layout="http://www.ultraq.net.nz/thymeleaf/layout"
      layout:decorate="~{fragments/core/layout}">
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
- Le pagine dell'app stanno in `templates/app/`, quelle del core in `templates/core/`; i nomi di vista Java sono `app/<pagina>` e `core/<pagina>`.

## Pattern controller: fragment vs pagina intera

**Stessa URL, due risposte**, distinte dall'header `HX-Request`:

```java
@GetMapping("/{id}")
public String status(@PathVariable Long id,
                      @RequestHeader(value = "HX-Request", required = false) String hxRequest,
                      Model model) {
    // ... popolare il model ...
    boolean isHtmxRequest = "true".equalsIgnoreCase(hxRequest);
    return isHtmxRequest ? "fragments/app/generation :: status" : "app/generation-status";
}
```

Un fragment con parametri restituito come vista di risposta diretta richiede parametri **nominati**
(`frag(nome=${valore})`); la forma posizionale funziona solo in un `th:replace` dentro un altro template, altrimenti 500
(`Parameters in a view specification must be named`). Esempi: `GenerationController` (fragment senza parametri),
`GalleryController` (paginazione, `hx-target="#gallery-content"` + `hx-swap="innerHTML"`, parametri nominati).

Un controller (adapter `in.web`) parla solo con le porte `in` (`IGenerations`, `IChat`...) mai con repository o classi `application`.

## Convenzione: attributi che portano un URL dell'app

Ogni attributo con un URL dell'app (`href`, `src`, `action`, `hx-get/post/put/delete`, URL passati a Web Component come
`connect` di `<deep-chat>`) passa **sempre** da `@{...}`, anche se il path e' letterale: l'app puo' stare dietro un
reverse proxy su subpath (`server.forward-headers-strategy`) e solo `@{...}` applica `X-Forwarded-Prefix`. Bug reale gia'
capitato (vedi `fragments/app/generate-form.html`, `templates/app/deep-chat.html`).

- Per `hx-*` basta il prefisso `th:` con `@{...}` dentro:
  ```html
  <form th:hx-post="@{/generations}" hx-target="#generation-panel" hx-swap="innerHTML"
        th:action="@{/generations}" method="post">
  <a th:hx-get="@{/gallery(page=${p})}" hx-target="#gallery-content" hx-swap="innerHTML">...</a>
  ```
- Fuori da htmx (es. `connect`, letto via JS): `th:attr` con `@{...}` dentro una literal substitution `|...|`
  (vedi `templates/app/deep-chat.html`).
- **Lato Java**: `request.getContextPath() + "/gallery"`, mai `"/gallery"` letterale (`ForwardedHeaderFilter` include il
  prefisso). Es. `GenerationController#delete`/`#deleteImage` per l'header `HX-Redirect`. `redirect:"..."` come nome di
  vista non ne ha bisogno.

## Convenzione: theming

Nessun file CSS: solo Tailwind, config in `src/main/tailwind/tailwind.config.js` (un solo file, vedi "Cosa NON introdurre"). Mai colori
hardcoded fuori da `theme.extend.colors`.

- **Token**: un blocco `{ DEFAULT, dark }` per colore (`canvas: { DEFAULT: '#ffffff', dark: '#0d1117' }`), usato come
  `bg-canvas dark:bg-canvas-dark` (il suffisso `-dark` e' solo una shade in piu'; il prefisso `dark:` decide quando usarla).
  Nomi: `canvas`/`surface`/`ink`/`ink-muted`/`line`/`accent`/`accent-contrast`/`danger` (+ `favourite`, `warning`).
- **Dark mode**: `darkMode: ['selector', '[data-theme="dark"]']`, non `prefers-color-scheme`. Il toggle light/dark/auto
  (Alpine su `<body>` + localStorage/`prefers-color-scheme`) scrive `data-theme`. Lo script di boot inline in `<head>`
  (prima di Tailwind) risolve `auto` e setta `data-theme` PRIMA della compilazione delle classi (evita il FOUC): non
  spostarlo piu' in basso.
- **`@layer base`** (in `fragments/core/layout.html`): solo per elementi "nudi" identici in piu' punti (link, `<code>`, form controls
  senza wrapper, `[x-cloak]`). Specificita' bassa: le classi inline vincono sempre. Tutto il resto e' utility inline, mai
  una nuova regola `@layer`.
- **Bottoni**: mai `<button>` a mano; usare i fragment di `fragments/core/button.html` (`primary`/`neutral`/`danger`/`themeToggle`/
  `dialogOpen`/...) e, per le azioni specifiche dell'app (reset, AI enhance, stop, toggle galleria, overlay dei thumbnail),
  `fragments/app/button-gen.html`; sempre con parametri nominati e passando TUTTI i parametri dichiarati (gli inutilizzati a `null`, es. `hxPost`
  su `danger` se htmx sta sul `<form>`). Se nessuna variante calza, aggiungere un fragment li' (generico → core, altrimenti app).
- **Binding Alpine** (`@click`, `:class`, `:title`, `:placeholder`...) non passano da `th:attr` ne' da `#{...}`: il
  valore dinamico si porta in un attributo `data-*` renderizzato da Thymeleaf (`th:data-theme-value="${value}"`) e si
  legge a runtime con `$el.dataset.themeValue`, lasciando l'espressione Alpine HTML statico. Vale anche per le stringhe
  i18n client-only (vedi toggle impostazioni in `templates/app/deep-chat.html`).
- **Limite**: le varianti Tailwind gestiscono solo due stati per colore (default + `dark:`); un terzo tema (es.
  "high-contrast") richiederebbe di ripensare `theme.extend.colors`, non e' un costo fisso.

## Convenzione: interfacce e storage dei binari

- **Interfacce**: il nome inizia SEMPRE con `I` (`IImageStorageService`, `IGenerations`, `IVectorIndex`); le implementazioni no e dicono il
  ruolo o il backend (`ImageStorageService`, `LocalFsBlobBackend`, `JpaGenerationStore`). Vale per ogni nuova interfaccia, porte comprese.
- **Storage dei binari** (`core.storage`): tutto cio' che l'app serve come file (immagini, mp4, upload sorgente)
  passa da `IImageStorageService`; nessun accesso diretto al filesystem/WebDAV altrove e nessun resource handler statico:
  `/images/**` lo serve `ImageController` leggendo dallo storage. Un nuovo tipo di binario si aggiunge li', non a parte.
  Lo use case `ImageStorageService` (`application`) tiene la logica comune (download, magic bytes, nomi, confinamento del filename);
  il backend (porta out `IBlobBackend`: `write`/`remove`/`size`/`openRange`) e' scelto da `storage.type` (`local` default | `webdav`) con
  `@ConditionalOnProperty` sul bean (`LocalFsBlobBackend` | `WebDavBlobBackend`), alternativi (passando a WebDAV i file locali esistenti
  non sono raggiungibili finche' non si esegue la migrazione). Il download di un URL remoto e' dietro `IRemoteFileFetcher`
  (`HttpFileFetcher`); la porta non espone `MultipartFile` ma `UploadedFile`.
- **Nomi dei file**: OGNI binario nuovo (output di una generazione, upload) si chiama `<sha256 di 32 byte casuali,
  hex>.<ext>` (`StorageNames#newFilename`), mai derivato da id, URL o nome originale: niente collisioni
  nemmeno dopo un reset del DB e nessuna informazione sul contenuto. NON e' un hash del contenuto (nessuna dedup: una riga
  = un file, cancellare non tocca le altre). L'estensione e' solo un'indicazione (su WebDAV il file e' cifrato). Il
  filename e' opaco per l'app: i file storici (`<id>-<n>.<ext>`, `upload-<uuid>.<ext>`) restano validi, nessuna migrazione.
- **Layout fisico annidato** (local e WebDAV): il filename resta piatto (DB, URL `/images/{file}`), ma sul backend vive in
  `ab/cd/<filename>` con `abcd` = primi 2 byte hex dello SHA-256 del FILENAME (`StorageNames#shardPath`:
  derivabile dal solo filename, nessuna colonna in piu'). Su WebDAV le collezioni `ab` e `ab/cd` si creano con MKCOL alla
  prima scrittura. Nessuna migrazione dei file preesistenti: i vecchi file piatti non sono piu' serviti.
- **WebDAV**: contenuti SEMPRE cifrati (AES-256-GCM a chunk da 64 KiB, `ChunkedAesGcmCipher` nel kernel: autenticato, Range/seek
  senza decifrare tutto) con la chiave base64 `storage.webdav.encryption-key` (env `STORAGE_WEBDAV_ENCRYPTION_KEY`, mai nel
  repo; avvio fallisce se manca/non e' 32 byte; persa la chiave i binari sono irrecuperabili). Solo i contenuti sono
  cifrati, i nomi file no. Client = `RestClient.Builder` iniettato (PUT su `.part` + MOVE, GET con Range, HEAD, DELETE,
  MKCOL), nessuna libreria WebDAV.
- **Migrazione locale → WebDAV** (`LocalToWebDavMigrator`, `application`, sopra la porta `IBlobImportTarget` implementata da
  `WebDavBlobBackend`): una tantum, opt-in con `storage.migration.from-local.enabled=true` + `storage.type=webdav`; parte
  all'avvio (`ApplicationReadyEvent`) sui file di `storage.images-dir` (esclusi i `.part`), salta quelli gia' sul server (HEAD:
  riavviabile, idempotente), un file che fallisce non ferma gli altri (`ISystemEvents`, operation `migrateLocalToWebDav`). I locali
  restano, salvo `delete-local=true`: ognuno si elimina solo se la dimensione in chiaro riportata dal SERVER coincide. Finche' non ha
  finito, i file non migrati non sono serviti; a fine giro rimettere `enabled=false`.
- **Cache locale** (`EncryptedBlobCache`, `storage.webdav.cache.*`, default 2 GB in `./data/cache`, `0` = off): tiene i
  blob CIFRATI (mai il chiaro), write-through alla scrittura e read-through su miss, eviction LRU, blob oltre il tetto
  letti a range direttamente da WebDAV. Nomi immutabili e unici: nessuna invalidazione se non su `delete`. Un errore di
  cache non fa fallire la richiesta (registrato con `CoreEventSource.STORAGE`).

## Convenzione: migrazioni database (Flyway)

`ddl-auto: validate`: Hibernate controlla solo che lo schema Flyway corrisponda alle entity (altrimenti l'app non parte).
Ogni modifica alla persistenza (entity, campo, indice, rename...) richiede una **nuova migrazione SQL** (DDL **PostgreSQL**).
Due location, una per lato: `src/main/resources/db/migration/core/` (tabelle del core: `system_event`, `api_token`...) e
`src/main/resources/db/migration/app/` (generation*, `replicate_model` + seed, chat_*, `vector_store` + `CREATE EXTENSION vector`,
`lora_preset`); `spring.flyway.locations` le elenca entrambe (in `application.yml`, perche' dipende da quante location ha l'app).

- **Numerazione a timestamp**: `V<AAAA>_<MM>_<GG>_<HHMM>__<descrizione>.sql` (es. `V2026_10_01_1200__core_baseline.sql`). Flyway fonde le
  due location in UNA sequenza e con `outOfOrder=false` un numero progressivo per location (core `V2` dopo app `V1000`) farebbe fallire la
  validazione: i timestamp li ordinano senza coordinarsi. Mai riusare un numero gia' applicato ne' modificare un file gia' eseguito: il
  checksum fa fallire l'avvio. Una migrazione nuova ha SEMPRE un timestamp successivo a tutte le esistenti (di entrambe le location).
- **Nessuna FK dal core all'app** (il core deve funzionare senza `db/migration/app`); le tabelle dell'app possono riferire il core solo se serve davvero.
  Le colonne di collegamento fra feature (es. `chat_message.generation_id`, `generation.conversation_id`) sono `Long` nelle entity (mai un
  `@ManyToOne` verso l'entity di un altro esagono). La FK nello schema c'e' solo nella direzione delle dipendenze (`chat_message.generation_id`
  -> `generation`, `ON DELETE SET NULL`: chat -> generation); `generation.conversation_id` NON ha FK (generation non conosce chat): chi cancella una
  conversazione scollega le sue generazioni con `IGenerations#detachFromConversation` (`ChatConversationService#delete`).
- Regole del dialetto: identificatori **minuscoli non quotati** (Hibernate non quota), testo lungo `text` (nelle entity
  `@JdbcTypeCode(SqlTypes.LONGVARCHAR)`, MAI `@Lob`: su PG sarebbe `oid`), `timestamptz`, `bytea`, enum Java = `varchar` senza
  ENUM/CHECK di DB. Un modello nuovo si censisce con un `INSERT` in `replicate_model` (`version` NULL = "ultima versione"): `form_type` e' un
  varchar, niente `ALTER` di ENUM. I baseline `core` e `app` sono lo storico squashato (passaggio a Postgres e poi alla divisione core/app,
  senza migrare i dati). In sviluppo si riparte da zero con `docker compose down && rm -rf data/postgres`.
- `FlywayCoreAppMigrationTest` prova core+app e una migrazione core piu' recente applicata dopo.

## Convenzione: internazionalizzazione (i18n)

Tutto il testo utente-visibile (template e messaggi d'errore Java) passa da `MessageSource` + `#{...}`, mai stringhe
hardcoded. La lingua segue `Accept-Language` (`AcceptHeaderLocaleResolver`, default Boot: nessun bean da scrivere, niente
switcher/cookie/sessione). Bundle (`spring.messages.basename: messages,messages-core`, in `core.yml`):
- `messages-core.properties` / `messages-core_en.properties`: il core (`html.lang`, `header.menu.*`/`header.theme.*`, `events.*`, `bell.*`,
  `toast.*`, `pagination.*`, `tokens.*`, `imagestorage.*`, `webdav.*`...) e le etichette delle sorgenti del core (`events.source.STORAGE|TOKENS|INTERNAL`);
- `messages.properties` (italiano, default/fallback anche per locale non mappate) / `messages_en.properties`: l'app (incl. `app.brand|title|footer`,
  `events.source.REPLICATE|OPENROUTER|SEARXNG|LORAS`, `events.link.generation|conversation`).
Le chiavi dei due bundle sono **DISGIUNTE** (niente shadowing: lo impone `TemplateRenderingTests.coreAndAppBundlesDefineDisjointKeys`); una chiave
nuova va nel bundle del lato a cui appartiene il codice che la usa. Nuova lingua: nuovi `messages_<locale>.properties` e
`messages-core_<locale>.properties` con le stesse chiavi e aggiornare `TemplateRenderingTests.messageBundlesHaveMatchingKeys`. Chiavi
punto-separate `<pagina-o-componente>.<categoria>.<elemento>` (`header.nav.home`, `replicate.error.tokenMissing`); i due bundle sono
comunque un unico namespace per template ed errori Java.

- **Apostrofi (bug silenziosi)**: Spring usa `MessageFormat` SOLO con argomenti non nulli. Senza parametri
  (`#{key}`, `Messages.get(code)`) gli apostrofi restano letterali (`l'app`); con parametri (`#{key(${arg})}`,
  `Messages.get(code, args...)`) vanno raddoppiati (`''`) o spariscono. Argomenti numerici (id, durate) passano per
  `NumberFormat` con separatori di migliaia: usare `{0,number,#}`, non `{0}`.
- **Lato Java**: iniettare `org.dual.replicate.core.kernel.i18n.Messages` (wrapper su `MessageSourceAccessor`, locale della richiesta
  via `LocaleContextHolder`) ovunque un errore possa arrivare all'utente (oggi `ReplicateClient`, `SearxngClient`,
  `GenerationService`, `ImageStorageService`, `GenerationController`, `DeepChatApiController`, `ChatService`).
  Risolvere al call site, prima di costruire l'eccezione, mai nel costruttore. Se la classe ha gia' una variabile
  `messages` (es. `ChatService`), chiamare il campo iniettato altrimenti (li' `i18n`).
- **`<html lang>`** viene dal bundle (`html.lang=it|en`, `th:lang="#{html.lang}"` sul decoratore), non da
  `#{#locale.language}`: su una locale non mappata il contenuto e' comunque italiano.
- **Limiti accettati**: `Generation.errorMessage` e' salvato gia' tradotto nella locale di chi ha generato l'errore (resta
  congelato); il catch-all di `DeepChatApiController` traduce solo il prefisso `"Errore nel contattare l'assistente: "`,
  non i messaggi di eccezioni di librerie terze.

## Convenzione: errori ed eventi di sistema, errori delle chiamate remote e stati terminali

Il registro errori e' un **registro generico di eventi di sistema** (`SystemEvent`, tabella `system_event`, pagina `/system/events`;
`/errors` reindirizza), classificati per severita': `ERROR` (gli errori di sempre) e `WARNING` (avvisi: oggi la scadenza dei token,
altri seguiranno; `SystemEventSeverity#isAtLeast` per le soglie). Ogni evento ha source (`EventSource`: interfaccia nel kernel,
implementata da `CoreEventSource` e `AppEventSource`; etichetta da `events.source.<NAME>`), `operation`, `subject` opzionale (a cosa si
riferisce: `token:12`, `generation:12`, `conversation:5`, vedi `AppEventSubjects`; il link "apri" lo risolve `IEventLinkResolver`/`AppEventLinks`)
e `acknowledgedAt` (visualizzato, per la campanella). La porta e' `ISystemEvents` (impl `SystemEventService`).

- **ERRORI**: ogni chiamata a Replicate, OpenRouter (Spring AI) o SearXNG, e ogni errore interno non gestito, passa da
`ISystemEvents#record(operation, throwable[, subject])` (la source si ricava da `RemoteServiceException#source()`, `ISystemEvents.sourceOf`;
la forma `record(source, operation, throwable[, subject])` resta per gli errori non remoti, `CoreEventSource.INTERNAL`): MAI un `catch`
che ingoia o soltanto logga. `record` logga con stack, salva/aggiorna una riga `system_event` (transazione propria, non lancia mai;
consultabile da `/system/events`, `SystemEventController`) e alla prima occorrenza di una *serie* consegna il toast a `IToastNotifier`
(`PushToastNotifier`) → SSE `system-event` → toast in tutte le tab con `live-events.html`. Serie = stesso (severity, source, operation,
subject, tipo eccezione) entro 5 minuti: aggiorna `occurrences`/`last_seen_at` invece di creare riga/toast a ogni poll durante un outage.
La query di serie (`SystemEventRepository#findOpenSeries`, adapter `persistence` del core) ha predicati null-safe scritti a mano su `subject`: un JPQL
`= :x` con :x null non combacia mai e ogni evento senza `subject` creerebbe riga e toast nuovi.
- **AVVISI**: `ISystemEvents#warn(source, operation, subject, message)` (messaggio gia' tradotto), stessa semantica (non lancia,
  un toast per serie) ma finestra di serie `app.events.warning-series-window` (24h): un controllo periodico non deve ripetere il toast
  ogni pochi minuti. Toast con `severity` nel payload (`WARNING` = bordo `warning`, token colore in `tailwind.config.js`).
- **Campanella** (`fragments/core/notification-bell.html`, nell'header fuori dallo slideover): si accende (badge, `danger` se c'e' un ERROR,
  altrimenti `warning`) quando ci sono eventi NON visualizzati di severita' >= WARNING; il pannello elenca gli ultimi 5; il click
  porta a `/system/events?event=<id>` (marca quell'evento come visualizzato e lo evidenzia). Il contenitore statico non viene mai
  sostituito (stato Alpine `open`), il contenuto si ricarica da `GET /system/events/bell` al load, ogni 30s e sugli eventi client
  `system-toast` (window) / `system-event` (body). "Segna tutti come letti" = `POST /system/events/seen` (`ISystemEvents#markAllSeen`). Una
  ripetizione di una serie gia' visualizzata NON torna non letta; chi rimuove la causa (token rinnovato/cancellato) chiama
  `ISystemEvents#markSeenBySubject`.
- **Eventi client**: `system-toast` (window, toast; anche via header `HX-Trigger`) e `system-event` (body, ricarica lista/campanella;
  stesso nome dell'evento SSE).

- **Overlay "operazione in corso"** (`fragments/core/busy-overlay.html`, incluso da `layout.html`, core): blocca l'INTERA UI (`inert` su header/main/footer; i
  toast restano fuori) finche' una richiesta non finisce, cosi' un secondo click non innesca una seconda operazione (es. una seconda prediction a
  pagamento). Regola di DEFAULT: ogni richiesta htmx NON-GET blocca (i GET di polling/paginazione/SSE no); sull'elemento htmx `data-busy="off"|"on"`
  (opt-out di un non-GET leggero / opt-in di un GET lento), `data-busy-text` (messaggio gia' tradotto, senza: `busy.default`), `data-busy-delay` (ms
  prima che la grafica si veda, default 250: il blocco e' immediato, lo sfondo/spinner/barra compaiono solo se l'attesa e' lunga, niente lampeggio
  sulle azioni rapide). Si chiude al `loadend` dell'XHR (successo, 4xx/5xx, rete, timeout, abort): il fallimento lo mostra il canale toast di sempre.
  Se la risposta e' una navigazione (`HX-Redirect`/`HX-Location`/`HX-Refresh: true`) NON si chiude e diventa subito visibile: sparisce con la
  pagina; `pageshow` persisted (bfcache) la azzera. Un `<form method=post>` nativo blocca fino all'unload. Watchdog `htmx.config.timeout` 180 s
  (> read-timeout del server) + toast su `htmx:timeout`. Fuori scope: i turni di /deep-chat (fetch del Web Component, ha il proprio stato di attesa).
  Un nuovo endpoint lento non richiede codice: basta un `hx-post/put/delete` (e un `data-busy-text` se serve un messaggio specifico).
- **Toast** (`fragments/core/toast.html`, incluso da `layout.html`): ascolta l'evento window `system-toast` ({key, message, transient, severity}),
  dedupe per `key`. Sorgenti: SSE; header `HX-Trigger` (`HtmxEvents#addToastHeader(response, ToastMessage)` e `#addHxTrigger(response, event, detail)`,
  nel kit `core.web`: l'esito `ISystemEvents.Recorded` e' un `ToastMessage`; la porta `ISystemEvents` non conosce il protocollo HTTP;
  usati da `GenerationController` create/enhance/cancel, `LoraController`, `TokenController`, `SemanticSearchController` e
  `UnhandledExceptionResolver`); listener globali `htmx:responseError`/`htmx:sendError` (solo se la risposta non portava gia' un toast).
- **Chi registra**: dove l'eccezione e' *gestita/ingoiata* (servizi in background, tool, watcher); se risale a un
  controller la registra il controller (`create`, `enhancePrompt`). `UnhandledExceptionResolver` (LOWEST_PRECEDENCE) e
  `AsyncErrorConfig` sono la rete per il resto.
- **Nessuno stato indefinito**, tre reti: (a) `IGenerations#refresh` (`GenerationService`) non lascia stati parziali: download/
  post-processing in try/catch → `FAILED` + file ripuliti; un errore di poll *transitorio* (`RemoteServiceException#isTransient`:
  rete, timeout, 429/5xx) NON fallisce la generazione ma il timeout di business vale comunque e annulla la prediction; uno
  *permanente* (4xx) la fallisce subito. (b) `GenerationRecoveryService` (adapter scheduling di generation) all'avvio
  (`ApplicationReadyEvent`, `@Order(AppStartupOrder.GENERATION_RECOVERY)`) fa avanzare ogni PENDING/PROCESSING; poi `ChatRecoveryService`
  (`ChatRecoveryScheduler`, `@Order(AppStartupOrder.CHAT_RECOVERY)`) riavvia i watcher persi e scrive i turni mancanti. (c) gli stessi servizi, ogni
  `app.recovery.sweep-interval`, chiudono le righe oltre timeout e scrivono i turni di chat mancanti (`ChatGenerationWatcher#persistOutcome`,
  idempotente). Disattivabile con `app.recovery.enabled=false` (i test).
- **Cancellare o far scadere** una generazione in corso annulla la prediction (`cancelPredictionQuietly`); se `create`
  non riesce a salvare la riga dopo aver creato la prediction, la annulla.
- **Chat**: se l'LLM fallisce, `SpringAiAssistant` lancia `AssistantException` (con gli id delle generazioni gia' avviate dai tool: avranno
  comunque il watcher) e `ChatService#reply` scrive un turno ASSISTANT d'errore (`ChatMessage.error`: in
  rosso, mai rimandato all'LLM) e lancia `DeepChatFailedException` (gia' registrata: `DeepChatApiController` mostra solo il
  messaggio). I tool (`WebSearchTool`, `ImageGenerationTool`, `ArchiveSearchTool`) catturano da soli e rimandano il testo d'errore al modello.
- **WebDAV** (`WebDavBlobBackend`, via `RemoteCaller` come gli altri): PUT/MOVE/DELETE/MKCOL e gli HEAD della
  migrazione ritentano (`RetryPolicy.DEFAULT`) i soli transitori; le letture per `/images/**` NO (`RetryPolicy.NONE`: il
  browser riprova, un retry allungherebbe la richiesta). `NoSuchFileException` (404) e' `passThrough`, non un errore. Un `.part`
  che non si riesce a ripulire e' registrato (`cleanupPart`); un file che non si riesce a cancellare resta orfano (registrato
  `deleteFile`, nessun recupero automatico). `IBlobBackend` e `IImageStorageService` lanciano SOLO `StorageException` (mai `UncheckedIOException`):
  `REJECTED` per gli esiti attesi (file inesistente, upload troppo grande/di tipo non valido).
- **Timeout**: `spring.http.clients.connect-timeout/read-timeout` valgono per tutti i `RestClient.Builder`
  auto-configurati (Replicate, download, Spring AI); SearXNG ha un timeout piu' stretto proprio. Un nuovo client HTTP
  usa il `RestClient.Builder` iniettato, mai `RestClient.create()`.
- **Locale**: `SystemEventService` risolve il toast con la locale del thread; un thread async la imposta prima
  (`ChatGenerationWatcher#watch`), il recupero usa l'italiano.

### Errori e retry generici (`core.kernel.remote`) e checklist "nuovo servizio remoto"

Un solo tipo, un solo esecutore, una sola traduzione HTTP:

- **`RemoteServiceException`** (radice di `ReplicateException`, `SearxngException`, `StorageException`, `OpenRouterException`, `TokenException`)
  porta `source()` (un `EventSource`) e `kind()`: `TRANSIENT` (rete/timeout/408/429/5xx, ritentabile), `PERMANENT` (4xx, 507, risposta illeggibile),
  `CONFIGURATION` (token/credenziali mancanti), `REJECTED` (rifiuto applicativo ATTESO: validazione, "non trovato", rifiuto del
  modello). `isReportable()` = tutto tranne `REJECTED`: solo i reportable si registrano/notificano (`GenerationController#create`
  ne decide cosi' "toast o solo form"); il resolver risponde 502 (guasto di un servizio esterno), 422 (`REJECTED`, senza riga
  in `system_event`, con un toast htmx del solo messaggio) o 500 (bug interno). `ReplicateException(String)` = `REJECTED`; con
  causa = `PERMANENT`: un errore vero senza causa va costruito con `Kind` esplicito.
- **`RemoteCaller#call(operazione[, RetryPolicy], supplier)`**: traduce qualunque eccezione e ritenta solo i `TRANSIENT`.
  `RetryPolicy` sempre esplicita per le operazioni NON idempotenti/a pagamento: `RetryPolicy.NONE` (es. `createPrediction`: un
  ritentativo potrebbe fatturare una seconda prediction). Un errore gia' classificato non viene ritradotto anche se RestClient lo
  ha incapsulato in una `ResourceAccessException`. OpenRouter/Spring AI ritenta gia' da se' (`spring.ai.retry.*`, a livello HTTP,
  prima dei tool): `OpenRouterCalls.CALLER` traduce senza un secondo strato di retry (rieseguire un turno rieseguirebbe i tool).
- **`RestClientTranslator`**: unica regola stato HTTP -> `Kind`; messaggi da `<prefix>.error.httpError|connectionFailed`.

Per aggiungere un servizio remoto:
1. Valore in `AppEventSource` (app) o `CoreEventSource` (core) + `events.source.<X>` nel bundle del lato giusto (`messages*` o `messages-core*`) nelle due lingue.
2. `class FooException extends RemoteServiceException` (costruttore `(String message, Throwable cause, Kind kind)`), nel `domain` o nell'adapter del sottosistema.
3. Client (adapter `out`) `extends RestRemoteClient` con prefisso `foo` (chiavi `foo.error.httpError|connectionFailed` nel bundle), che implementa la porta `out`
   del sottosistema (es. `SearxngClient` → `IWebSearchGateway`, `ReplicatePredictionGateway` → `IPredictionGateway`), e ogni
   chiamata in `remote.call("operazione", () -> ...)` (`RetryPolicy.NONE` se non idempotente). Esempio minimo: `RestRemoteClientTest`.
4. Chiamante in background: `systemEvents.record("operazione", e, ...)`; controller: `systemEvents.record(...)` + `HtmxEvents#addToastHeader`
   oppure lasciar risalire (il resolver registra con la source giusta). Niente `catch` che ingoia.
- **Front end**: nessun codice per servizio. Il toast (`fragments/core/toast.html`) e' guidato dal payload `{key, message,
  transient}`; `transient: true` aggiunge "Riprova tra qualche istante". `HtmxEvents#addHxTrigger` FONDE gli eventi
  nell'unico header `HX-Trigger` (un controller puo' emettere `gallery-update` e un toast insieme). Un bottone "Riprova" generico
  sul toast e' escluso di proposito: rieseguire una POST (`create`) creerebbe una seconda prediction a pagamento.

## Token API (CivitAI, HuggingFace) e cifratura dei segreti

CRUD in `/tokens` (`core.tokens`: `TokenController`, `IApiTokens`/`ApiTokenService`, `fragments/core/tokens.html`: dialog Pines come le note di
`/search`), per scaricare LoRA privati con flux-dev-lora. Il core non sa NULLA dei servizi: `ApiToken.provider` e' una stringa e i servizi
offerti li elenca l'app implementando `ITokenProviderCatalog` (`AppTokenProviders`, valori di `ApiTokenProvider`; etichette `tokens.provider.<NAME>`).
Il token si salva con un NOME (unico per provider) e una scadenza facoltativa (data inserita a mano:
nessuno dei due servizi la espone), si sceglie per nome nelle select delle form (`hfTokens`/`civitaiTokens` nel Model, solo dove si
renderizza il fragment del form-type: `GenerationController`, `DeepChatController`; `GenerationFormRegistry#extraFormOptions` su `IApiTokens#options`).
Dopo il salvataggio non si vede piu': la UI mostra solo gli ultimi 4 caratteri (`token_hint`). Mai il segreto in log, eventi, toast o modello Thymeleaf.

- **Cifratura**: `ISecretCipher` (impl `SecretCipher`, `core.secrets`) usa la STESSA chiave e lo STESSO algoritmo dei binari WebDAV
  (`ChunkedAesGcmCipher`, AES-256-GCM; `encryptBytes`/`decryptBytes` per i valori piccoli): `app.secrets.encryption-key` = `${storage.webdav.encryption-key}`
  (in `core.yml`), cioe' `STORAGE_WEBDAV_ENCRYPTION_KEY`, nessun segreto nuovo. Con `storage.type=local` la chiave NON e' obbligatoria all'avvio
  (il segnaposto di `.env.example` non e' base64 valido): senza, `ISecretCipher#isConfigured()` e' falso, `/tokens` mostra un alert e salvare un
  token e' un errore `CONFIGURATION` (`SecretException`/`TokenException`). Chiave persa = token irrecuperabili (come i binari); nessuna rotazione;
  il ciphertext non e' legato alla riga. `pom.xml` fissa una chiave di test per Surefire (il `.env` reale non deve cifrare nei test).
- **Uso**: `GenerationService#doCreate` -> `TokenInputResolver#resolveInto(input)` -> `IApiTokens#resolve(id, provider)` (vedi "LoRA al volo").
  Scaduto o inesistente = `TokenException` `REJECTED`, nessuna prediction a pagamento parte.
- **Scadenza**: `TokenExpiryScheduler` (adapter scheduling del core: all'avvio e ogni `app.tokens.expiry-check-interval`, spento nei test con
  `app.tokens.expiry-check-enabled=false`) chiama `IApiTokens#checkExpiries`: per i token scaduti o scadenti entro
  `app.tokens.expiry-warning-days` (15) registra un AVVISO (`CoreEventSource.TOKENS`, operation `tokenExpiring`/`tokenExpired`, subject
  `token:<id>`). Creare/modificare un token con scadenza vicina avvisa subito.

## Convenzione: select (Pines)

Mai una `<select>` nuda: ogni selezione usa il componente `fragments/core/select.html` (Alpine + Tailwind, stile Pines; test strutturale
`everySelectIsWrappedByThePinesSelectComponent`). La `<select>` nativa RESTA nel DOM (`sr-only`) come fonte di verita': `FormData`,
`hx-trigger="change"`/`hx-include`, `required`, `x-model`, il sync di `templates/app/deep-chat.html` e i test sulle `<option selected>` non cambiano; il
componente e' solo la UI, legge le `<option>` e scrive il valore con gli eventi nativi `input`+`change`. Uso:

```html
<div class="relative" x-data="pinesSelect" x-bind="root">
    <select id="..." name="..." class="sr-only" tabindex="-1" aria-hidden="true"> ...<option>... </select>
    <div th:replace="~{fragments/core/select :: ui}"></div>
</div>
```

Un cambio di valore da codice (senza eventi) va annunciato con `select.dispatchEvent(new Event('pines-select:sync'))` (vedi `templates/app/deep-chat.html`,
`button-gen :: resetToDefaults`). Se il pannello fosse tagliato da un contenitore con overflow: fallback `@alpinejs/anchor` o posizione `fixed`.

## Convenzione: stato delle form di parametri di generazione (client)

Modello + parametri (+ prompt in `/generations/new`) sono una preferenza del browser, non dati applicativi: stanno in `localStorage` e cambiano
SOLO per modifica dell'utente o "Reimposta ai default" esplicito, mai per una navigazione. Un solo script, `fragments/app/generation-settings-persist.html :: script`
(da includere DOPO il markup), attivo su ogni `form[data-persist-key]`: `/deep-chat` (`deepChat.generationSettings`) e `/generations/new`
(`generate.image|video|edit`, una chiave per tipo di pagina, separata dalla chat). Configurazione via `data-*` sul form:
`data-persist-key`, `data-persist-no-restore` (campi che il server ha valorizzato da un link esplicito, il prompt di "Anima": non si
ripristinano ma si scrivono), `data-shared-accept` (chiavi dello slot globale che il form prende; l'edit solo `seed`), `data-persist-ignore` (mai scritti: `version`, un hash pinnato ripristinato di nascosto userebbe il modello sbagliato a
pagamento). A ogni sync il form emette `generation-settings:sync` (detail = tutti i campi, hidden inclusi): la chat lo inoltra a
`window.setDeepChatSettings`, registrando il listener PRIMA dell'include. Il listener `input` e' delegato su `document` perche' il form di
`/generations/new` viene ri-renderizzato dopo un create rifiutato (`createFailed` rimette il `seed` nel Model: non e' fra i `defaultFields`). Non
persistiti: file `sourceUpload`, hidden. Un nuovo form-type non richiede nulla qui.

**Slot globale prompt/seed** (`localStorage['generation.shared']` = `{prompt?, seed?}`): "Usa prompt" (uno per generazione) e "Usa seed" (PER FILE, nella
griglia del dettaglio; `button-gen :: pushShared`, scrittura in `fragments/app/generation-shared-slot.html :: pusher`) NON aprono un caso d'uso: spingono nello
slot. Lo legge `applySharedSlot` nello script di persistenza (avvio, evento `storage` di un'altra tab, bfcache) e CONSUMA ogni chiave applicata; una chiave
resta se il form non la accetta, non ha il campo (il prompt in Deep Chat) o il server l'ha gia' valorizzata da un link ("Anima" vince). Il seed per file e'
`Generation#reusableSeedOf` (tabella `generation_image_seed`, solo se i log hanno un "seed" per output). Altrimenti c'e' un solo seed di batch, e
riproduce SOLO la prima immagine (verificato con due prediction su flux-lora-ff3: la seconda immagine di un batch nasce da un seed derivato che nessun log
riporta, e non e' seed+1): la prima mostra `(batch)` col bottone, le altre "non riproducibile da sola" senza bottone. Un seed vero per ogni immagine richiede
una prediction per immagine (`num_outputs=1`), non implementato.
La chiave dello slot e' duplicata nei due script: tenerle allineate.

## Ricerca semantica (PgVectorStore su PostgreSQL+pgvector, embedding locali)

Stesso Postgres dei dati, nessun servizio in piu'. Il sottosistema `app.search` espone solo tipi di dominio (`SearchableDocument`,
`IndexedDocument`, `ScoredDocument`, `DocumentFilter`, `IndexStats`) e NON fa uscire Spring AI (`Document`/`Filter`/`VectorStore`) dall'adapter
`adapter.out.vector`: chi vuole cercare per significato usa `IArchiveSearch` (porta in), l'indice e' dietro `IVectorIndex` (porta out,
impl `PgVectorIndex` sopra `PgVectorStore`): un domani si puo' sostituire con Elasticsearch/Qdrant cambiando quell'adapter.

- **Embedding**: `EmbeddingModel` locale (`TransformersEmbeddingModel`, ONNX) con `multilingual-e5-small` quantizzato (384 dim,
  italiano/inglese, ~120 MB). Il modello e il tokenizer si scaricano UNA volta al primo avvio da Hugging Face in `./data/models`
  (fuori da git; niente download in build). `spring.ai.model.embedding=transformers` evita che l'autoconfig OpenAI crei un secondo
  `EmbeddingModel`. I modelli e5 vogliono i prefissi `passage: ` (documenti) e `query: ` (ricerche): li applica
  `E5PrefixEmbeddingModel`, decorator che solo lo store vede (`getEmbeddingContent` = percorso dell'indicizzazione, `embed(String)` =
  ricerca), chi lo usa passa il testo nudo. I punteggi e5 sono compressi (0.7-0.9): usare top-K, non soglie fisse.
- **`PgVectorStore`** (`SemanticSearchConfig`; dipendenza `spring-ai-pgvector-store`, NON lo starter: niente autoconfig): tabella
  `vector_store` creata da Flyway (migrazione `app`, `initializeSchema=false`): `id text`, `content`, `metadata json`, `embedding vector(384)`.
  Punteggio = `1 - distanza coseno`. **Nessun indice ANN** (HNSW/IVFFlat) di proposito: `/search` vuole TUTTA la classifica sopra soglia
  (`IArchiveSearch#searchAll`: `topK` = numero di documenti) e un indice approssimato tronca a `ef_search` (40); lo scan esatto costa pochi ms a decine di migliaia
  di righe. Cambiare il modello con dimensioni diverse da 384 = nuova migrazione `ALTER ... TYPE vector(N)` (dopo aver svuotato la
  tabella) + reindicizzazione. Il filtro (`DocumentFilter`: `type`, `from`/`to` su `createdAt`) e' tradotto da `PgVectorIndex` in un `Filter.Expression` Spring AI
  e da questo in jsonpath: operatori **EQ, NE, IN, NIN, AND, OR, GT/GTE/LT/LTE** (NON NOT ne' ISNULL/ISNOTNULL); numeri solo per i
  confronti, una chiave assente non combacia. Soglia 0 = esclude solo la similarita' esattamente 0 (distanza `<` stretta).
- **Metadata**: ogni documento ha `type` (stringa, vedi `DocumentTypes`: `generation`, `chat`, `conversation`, `note`) e `refId` (numero), opzionali
  `conversationId`, `role`, `kind`, `title` e `createdAt` (epoch millis della CREAZIONE del contenuto; `SearchableDocument#of` li costruisce), piu' le
  chiavi **riservate** scritte da `VectorIndexer`: `contentHash` (SHA-256 del testo), `embeddingModel` (URI ONNX) e `indexedAt` (ultima
  indicizzazione; `IndexedDocument#createdAt` ricade su di esso se manca `createdAt`). Nello store non ci sono colonne per hash/modello: stanno li'.
- **`VectorIndexer`** (scrittura, unico punto da cui l'app aggiunge/cancella/ri-embedda): salta i documenti invariati (stesso hash e
  modello), se cambiano solo i metadata li riscrive senza ri-embeddare, altrimenti `vectorStore.add` (upsert). Cambiare modello (URI ONNX =
  id del modello) => alla riconciliazione successiva si ri-embedda tutto. **`VectorDocumentRepository`** (lettura JDBC): `find`, `list`
  (paginato, piu' recenti prima per `createdAt`), `countsByType`, `idsOfType`, `count`; il filtro usa lo stesso convertitore jsonpath dello store.
- **`ArchiveIndexService`** (`IArchiveIndex`) allinea l'indice con una riconciliazione idempotente (non ganci su ogni `save`): chiede a TUTTI i
  bean `ISearchableSource` i loro documenti (generation: prompt delle generazioni SUCCEEDED, `type=generation`; chat: messaggi non d'errore,
  `chat`, e titoli, `conversation`), aggiunge i mancanti/cambiati, rimuove i documenti dei `types()` dichiarati dalle sorgenti la cui
  riga non esiste piu'; la lettura delle sorgenti sta in una transazione read-only, le scritture sull'indice no. Gira in background
  (`ArchiveIndexScheduler`: all'avvio come backfill e ogni `app.search.reindex-interval`) e a ogni `GenerationCompletedEvent` (lo
  ascolta `GenerationSearchSource`, che chiama `IArchiveIndex#reindexAsync` solo se la ricerca e' attiva). Un documento che fallisce e'
  registrato (`ISystemEvents`) e non ferma gli altri. Una nuova fonte ricercabile = un nuovo `ISearchableSource` nel suo sottosistema.
- **`ArchiveSearchTool`** (`searchArchive(query, type?)`, in `chat.adapter.ai`, sopra `IArchiveSearch`) e' tra i tool di `SpringAiAssistant` solo se `app.search.enabled`.
- **Link alle generazioni in chat**: `searchArchive` restituisce al modello path assoluti (`/generations/12`). Dietro un reverse
  proxy su subpath non funzionerebbero, quindi `templates/app/deep-chat.html` li riscrive SOLO in visualizzazione (`linkGenerations`, su
  `responseInterceptor` e sulla cronologia) in link markdown RELATIVI alla pagina corrente (`/deep-chat` -> `generations/12`,
  `/deep-chat/5` -> `../generations/12`), senza dipendere da `X-Forwarded-Prefix`. Il testo salvato resta l'originale.
- `app.search.enabled=false` (i test, `application-test.yml`) spegne indice, scheduler, tool, servizi `IArchive*` ed `EmbeddingModel`
  (`spring.ai.model.embedding=none`): `mvn test` non scarica ne' carica mai il modello. I test usano un embedding finto (`FakeEmbeddingModel`).
  Prove reali, opt-in: `mvn test -Dtest='E5ModelSmokeTest,SemanticSearchWiringTest' -Dsemantic.model.test=true`.
- **UI `/search`** (`SemanticSearchController`, `templates/app/search.html` + `fragments/app/search.html`; link nell'header solo con `app.search.enabled`):
  **UN solo form** (testo, tipo, periodo dal/al, soglia) e **UNA sola lista paginata** (`GET /search/results`, target `#search-results`,
  20 per pagina): con testo e' la classifica per significato (punteggi in %, TUTTA la classifica sopra la
  soglia: nessun top-K nella UI, `app.search.top-k` resta solo per il tool della chat), senza testo si sfogliano i documenti, i piu'
  recenti prima (per `createdAt`); in entrambi i casi filtrati per tipo e per periodo di creazione (date ISO, estremi inclusi, fuso del
  server). Niente liste "non filtrate" a parte: non reintrodurle, sembrerebbero il risultato della ricerca. La paginazione conserva i
  filtri perche' il controller passa `baseQuery` (query string gia' codificata) e il template `'/search/results?' + ${baseQuery}`
  (`pagination :: nav` accoda `&page=N`). Il form si re-invia da solo (`input`/`change` e `note-saved`: una nota creata o modificata
  ricarica la lista coi filtri correnti; `POST /search/notes` risponde con le sole statistiche fuori banda). Mostra
  dettagli/metadata/modello/hash, statistiche (`IndexStats`) e "Riconcilia ora". `createdAt` lo scrivono `ArchiveIndexService#reconcile` (da
  `getCreatedAt` della sorgente, backfill a costo zero: cambiano solo i metadata) e le note (alla creazione, conservato in modifica;
  la riconciliazione timbra con `refId` quelle vecchie). Nei test, i documenti finti di tipo derivato (`chat`/`generation`) possono essere
  cancellati dalla riconciliazione di fondo del contesto: per liste lunghe usare `type=note`.
  **Solo le note manuali (`type=note`) sono creabili/modificabili/eliminabili** (`IArchiveNotes`, `ArchiveNoteService`): la riconciliazione non le crea ne' rimuove. I documenti
  derivati (generation/chat/conversation) sono in sola lettura (la fonte di verita' e' il DB, una modifica o cancellazione a mano
  verrebbe annullata al giro dopo): su di essi solo "Ri-embedda" (`IArchiveIndex#reembed`, anche dopo un cambio di modello). Un id non-nota su
  modifica/eliminazione => 422.
  "Nuova nota" e "Modifica" usano lo STESSO **dialog modale Pines** (`templates/app/search.html`, stessa meccanica del lightbox:
  `x-data="{ dialogOpen: false }"`, `x-trap.inert.noscroll`, senza teleport; bottoni `fragments/core/button :: dialogOpen|dialogClose|dialogCloseIcon`,
  che assumono `dialogOpen` su un antenato). `dialogOpen` con `hxGet` ricarica `#note-form` (`fragments/app/search :: noteForm`, vuoto per
  `GET /search/notes/new`, precompilato per `/search/notes/{id}/edit`) a ogni apertura. Al salvataggio riuscito il server emette
  `HX-Trigger: note-saved` (`HtmxEvents#addHxTrigger`) che chiude il dialog (risposta: solo le statistiche in creazione, riga `#doc-...`
  in modifica); con un errore di validazione risponde col form (`HX-Retarget: #note-form`) e il dialog resta aperto. Le statistiche
  si aggiornano fuori banda (`hx-swap-oob`).
  La ricerca ha una **soglia di somiglianza minima** in % (`threshold`, 0..100, default `app.search.similarity-threshold-percent`=0):
  i punteggi e5 sono compressi (tipicamente 70-90%), quindi la soglia utile e' alta.
  Test del controller con embedding finto: `SemanticSearchControllerTest`.
- Fuori scope per ora: ricerca semantica nelle liste/galleria esistenti, descrizioni delle immagini con un modello di visione.

## Comandi utili

Nessun Maven Wrapper (serve Maven installato; `mvn wrapper:wrapper` per generarlo).

```bash
docker compose up -d          # PostgreSQL+pgvector di sviluppo (DB_USERNAME/DB_PASSWORD nel .env, vedi .env.example)
mvn spring-boot:run          # sviluppo (Thymeleaf cache=false)
mvn test                     # test (include ArchitectureTest)
mvn test -Dtest=ArchitectureTest   # solo le regole di architettura (le altre richiedono Docker)
mvn clean package            # jar eseguibile (Tailwind via Play CDN)
mvn -Ptailwind clean package # + CSS Tailwind compilato/minificato (richiede rete per il binario)
```

`mvn test` richiede **Docker** e non tocca mai il DB di sviluppo: `PostgresTestContainerInitializer` (`support`, registrato in
`src/test/resources/META-INF/spring.factories`, quindi valido per ogni `@SpringBootTest` senza annotazioni) avvia UN container
`pgvector/pgvector:pg17` per tutta la suite e ne imposta il datasource; Flyway applica lo schema reale (core+app). Il profilo "test" di Surefire
(`<systemPropertyVariables>` in `pom.xml`) resta per il resto della config (`application-test.yml`: recupero, ricerca e controllo scadenze token spenti). I test condividono il DB: i
`@SpringBootTest` che scrivono ripuliscono a mano o sono `@Transactional`. I test stanno sotto `src/test/java` con la stessa struttura dei package
(`core/<s>/...`, `app/<s>/...`); `TemplateRenderingTests` (rendering di tutte le pagine/fragment, bundle) resta in `controller`.
Stesso principio per lo storage: `spring.config.import` carica il `.env` reale anche sotto Surefire, quindi `pom.xml` fissa
come proprieta' di sistema `storage.type=local` e `storage.migration.from-local.enabled=false` (battono qualunque file);
un test che vuole WebDAV o la migrazione li sovrascrive con `@SpringBootTest(properties=...)`, mai contro il server vero.

## Checklist per una nuova pagina/feature

1. Solo navigazione → nuovo controller in `adapter/in/web` del sottosistema giusto (che parla solo con le porte `in`) + template `templates/app/<pagina>.html`
   (o `templates/core/` se generico) col pattern layout manager; la voce di menu in `fragments/app/nav.html`.
2. Aggiornamento parziale (ricerca live, paginazione, form senza reload) → estrarre un fragment in
   `fragments/app/<nome>.html` (o `fragments/core/`); il controller lo restituisce se `HX-Request`, la pagina intera altrimenti.
3. Solo interattivita' locale → Alpine (`x-data`/`x-show`/`x-on`) nel template, senza controller.
4. Componente complesso stateful → valutare prima un Web Component isolato.
5. Tocca un'entity JPA → nuova migrazione Flyway a timestamp nella location giusta (`db/migration/core` o `db/migration/app`), mai `ddl-auto`; nessuna FK core → app.
6. Testo utente-visibile → chiave nel bundle giusto (`messages*` per l'app, `messages-core*` per il core) in entrambe le lingue, mai stringa hardcoded
   (template o eccezione); le chiavi dei due bundle restano disgiunte.
7. Serve un `<button>` → fragment di `fragments/core/button.html` (o `fragments/app/button-gen.html` per le azioni dell'app; aggiungerne uno se nessuno calza), mai inline.
8. Serve una `<select>` → wrapper `pinesSelect` (vedi "Convenzione: select (Pines)"), mai nuda.
9. Un evento che l'utente deve notare → `ISystemEvents#warn` (avviso) o `#record` (errore): finisce in `/system/events`, nel toast e nella campanella.
10. Nuovo sottosistema/feature, nuova porta, nuova dipendenza fra sottosistemi → vedi "Architettura" e la sua checklist; `ArchitectureTest` deve restare verde.
