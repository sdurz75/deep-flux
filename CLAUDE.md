# CLAUDE.md

Guida al repository: le scelte sono vincoli deliberati per tenere il progetto snello. Struttura, classi e package si ricavano dal repo (`git ls-files`) e dai test di architettura; qui solo cio' che non e' ovvio. Java 21, Maven multi-modulo (nessun Maven Wrapper), package radice `org.hexa`.

## Moduli

- `hexa-core`: libreria base `org.hexa.core.{kernel,web,events,push,secrets,tokens,storage,backup,manual}` + risorse core (`core.yml`, `messages-core*`, `db/migration/core`, `templates/{core,fragments/core}`).
- `hexa-ai`: libreria opzionale sopra core, `org.hexa.core.{ai,chat,search,credits}` (stessi package `core.*`, jar a parte) + `ai.yml`, `messages-ai*`, `db/migration/ai`, template chat/ricerca. `hexa-core` non dipende da `hexa-ai` (lo impone Maven).
- `deep-flux`: l'applicazione (host, `org.hexa.app.*`, `Application`, `application.yml`, `messages*`, `prompts.properties`, `db/migration/app`, `templates/{app,fragments/app}`, `manual/`, `tailwind/`).
- `hexa-bom` (versioni allineate di hexa-core/hexa-ai/hexa-test-support, da importare in un host; la sua versione segue il parent).
- `hexa-test-support` (container di test e `HexaArchitectureRules`: le regole di layering per un host con package radice qualunque, usate dall'`ArchitectureTest` generato dall'archetype), `hexa-archetype` (genera un'app su hexa, `-DuseAi=true` per hexa-ai: `docs/TEMPLATE.md`), `pom.xml` radice = parent/reactor.
- I test `@SpringBootTest` (anche di package `core.*`) stanno in `deep-flux`; in `hexa-core`/`hexa-ai` solo test senza contesto Spring e i test di autoconfigurazione con host fuori da `org.hexa` (`com.example.*`: `CoreOnlyHostTest`, `AiHostTest`); i loro helper (`FakeWebDavServer`, `FakeEmbeddingModel`) arrivano come test-jar. `.env` e `data/` stanno nella radice (`spring-boot:run` parte da li').

## Scopo

Single-user (`Generation` senza owner, multi-conversazione): (1) generare immagini/video con un chatbot, (2) archiviarle e cercarle, (3) storico conversazioni, (4) addestrare LoRA propri. Non aggiungere feature (demo, integrazioni, pattern) che non servano a questo; un pattern htmx/Alpine nuovo si dimostra in una feature vera. Il repo e' anche un template (si tiene `core`, si sostituisce `app`).

## Dominio (vincoli non ovvi)

### Generazione
- **Chat** (`/deep-chat`): `WebSearchTool` e `ImageGenerationTool` (Replicate). Il modello viene SEMPRE dal combobox UI (`MODEL_CONTEXT_KEY` in `ToolContext`), mai dall'LLM. E' l'UNICO tool a pagamento, tetto `app.chat.max-generations-per-turn` (3). Torna subito con l'id; il polling prosegue in background (`ChatGenerationWatcher`) e l'esito arriva come nuovo turno via SSE (`GET /events`): nessun polling client per la chat.
- `/generations/{id}`: polling htmx 2s fino allo stato terminale, poi UNICO dettaglio. Placeholder in `fragments/app/generation-placeholder.html` con stili INLINE (finisce nello shadow DOM di `<deep-chat>`). "Interrompi" = `POST .../cancel` → `FAILED`.
- **Video**: stessa pipeline, `Generation.kind` da `GenerationFormType#kind()`; unico modello `prunaai/p-video`; mp4 in `imageFilenames`; timeout 15 min (5 per le immagini). Sorgente "Anima" = solo immagine RIUSCITA (`findAnimatableSource`), mai un text-to-video silenzioso; upload sorgente (`storeUpload`: magic bytes png/jpeg/webp, 10 MB) ha precedenza. La chat propone SOLO modelli immagine. `disable_safety_checker` forzato solo per le immagini.
- **Modelli a sorgente obbligatoria** (kontext, fill-dev/pro): `GenerationFormType#sourceRequired()`, `maskRequired() = takesMask() && sourceRequired`, `isInstructionEdit() = sourceRequired && !takesMask()`. `IModelCatalog`: `models/contains/defaultModel` = solo modelli che funzionano SENZA sorgente (chat, default, reset); `formModels` = tutti (solo combobox del form). Senza sorgente `create` fallisce prima di Replicate. La sorgente da generazione sta in hidden `sourceGenerationId`+`sourceImage` FUORI da `#generation-params-fields`: `GET /generations/params` deve riceverla (select modello con `hx-include`, restore con `sourceQuery(form)`, "Reimposta"). Nel cambio modello `guidance`, `guidance_scale`, `num_inference_steps`, `steps` non si ereditano (`MODEL_SCALED_FIELDS`).
- **flux-dev-lora**: modello IMAGE normale con `lora_weights`/`extra_lora`, img2img opzionale. Token HF/CivitAI scelti PER NOME da `/tokens`; al server arriva l'ID, il chiaro esiste solo in `GenerationService#doCreate` (`TokenInputResolver`); token inesistente/scaduto = rifiuto senza prediction.
- **LoRA anagrafati** (`/loras`, `LoraPreset`): solo comodita'; la select preset non ha `name`, e' derivata e mai persistita. Una sorgente `owner/nome` Replicate censisce anche il modello come `FLUX_LORA_FINETUNE` (`registerLoraFinetune`, versione pinnata, idempotente; rifiuto silenzioso, guasto = evento). Cancellare il preset NON toglie il modello. Nei `@SpringBootTest` che creano preset serve `@MockitoBean IPredictionGateway`.
- **`FLUX_LORA_FINETUNE`** (es. `sdurz75/flux-lora-ff3`): UN form-type generico per tutti; un nuovo fine-tune = `INSERT` in `replicate_model` (`version` = hash pinnato). Resta text-to-image (`sourceRequired` falso). In `doCreate`: nessuna maschera = normale; maschera+sorgente = `image`+`mask`; maschera senza sorgente = `maskNeedsSource` prima di Replicate. Con un'immagine il modello ignora `aspect_ratio`/`width`/`height` e usa `megapixels`.
- **Inpainting** (fill-dev/pro): sorgente E maschera obbligatorie. Maschera = PNG dipinto nel browser (`mask-editor.html`, BIANCO = da ridipingere, bordi sfumati), inviato come `maskUpload` multipart (mai base64 in campi testo), salvata solo se `takesMask()`, eliminata con la generazione. Anteprima con `mask-overlay.html` (filtro SVG `luminanceToAlpha`; NON `mask-mode: luminance` ne' `mix-blend-mode`). fill-pro: niente LoRA/`num_outputs`, `safety_tolerance` forzato a 6. flux-dev-lora NON ha `mask`: per inpainting col proprio LoRA si usa fill-dev. "AI enhance" modello di visione NON vede la maschera.
- **Costo**: stimato (`ReplicatePricing` statica: ogni nuovo modello richiede la sua regola, tranne i `FLUX_LORA_FINETUNE`); `IGenerations#refresh` salva `cost_usd`.

### Archivio
- `/gallery` = solo SUCCEEDED, via SSE (`gallery-update`); `/generations` = tutte. Cancellare elimina i file; cancellare l'ultima immagine elimina la generazione. Preferiti per file (`favouriteFilenames`).
- **Tag utente**: normalizzazione UNICA `core.kernel.Tags` (minuscolo, niente virgole/virgolette/backslash perche' finiscono in un filtro jsonpath, max 40 car., 20 per entita'), match esatto; non sono i tag AI `analysisTags` ne' quelli d'indice `#tags:`. Una `<datalist id="known-tags">` per pagina (un solo editor con `suggestions=true`).
- **Immagini importate** (`origin=IMPORTED`, `model`/`externalId` NULL, `/import`, dettaglio PROPRIO `/import/{id}`): `prompt` = descrizione dell'analisi. Analisi `PENDING/DONE/FAILED` asincrona (`ImportAnalysisListener` → `IImageDescriber`, guida `imageAnalysis.guide` SENZA `prompts.creative-context`: i suoi limiti farebbero rifiutare foto di persone). Rifiuto = `FAILED` senza toast, guasto = `FAILED` + `record`. Indice solo con analisi `DONE`, tipo `imported` (metadata di `Document` non accettano null). `reuseConfig` le esclude.

### Conversazioni
- `ChatConversation`/`ChatMessage`. La chat conosce una generazione solo per `ChatMessage.outcomeRef` (riferimento opaco; FK `ON DELETE SET NULL` verso `generation`, dell'app).
- **Cronologia lato SERVER**: il client manda solo l'ultimo messaggio (`maxMessages: 1`); `ChatHistoryBuilder` la ricostruisce (niente turni d'errore, `app.chat.history-max-turns`/`history-max-chars`, parte da un turno utente, ruoli consecutivi fusi). Un esito (`IChatOutcomes#append`, idempotente) arriva al modello come nota `system` in INGLESE.
- **SPI verso l'host** (la chat non conosce la generazione): `IChatTurnContributor` (contesto del turno come `ToolContext`), `IChatPageContributor`, `IChatOutcomeResolver`, `IChatToolkit` (`beginTurn`/`endTurn`, `ChatTurnResult` con `extras` appiattiti: `generationIds`, `actions`). La pagina e' `core/deep-chat.html`; l'host la riempie con UN template (`app.chat.host-fragment`, slot `intro|settings|below|scripts|conversationTags|knownTags`); lo script di `scripts` definisce `window.deepChatHost` PRIMA del modulo di core.
- **Tool e prompt**: ogni `IChatToolkit` implementa `promptSection` e ha `@Order`; sezioni in `prompts.properties` (iniziali/finali configurabili `app.chat.prompt-sections.leading|trailing`); `ChatPromptTest` fissa il tetto di 10.000 caratteri. I RITORNI dei tool sono in INGLESE, dicono di non riprovare e di avvisare l'utente; i tool catturano da soli (rifiuto atteso com'e', guasto = `record`). Vincoli: `WebSearchTool` (risultati non fidati); `ManualTool` (sola lettura, solo gruppo `uso`, max 6.000 car.); `ArchiveSearchTool` (query vuota + filtro = piu' recenti, senza filtri rifiuta); `LibraryTool` (whitelist `VISIBLE_PARAMETERS`: mai LoRA, token o `parametersJson`); `CurationTool` (mutazioni IDEMPOTENTI, mai toggle; tag/rename solo sulla conversazione CORRENTE); `ActionProposalTool` (`propose*` NON eseguono: depositano una `ChatAction` resa come bottone; rigenera/anima aprono `/generations/new?...`); `VisionTool` (tetto `max-vision-calls-per-turn` 2; i video si rifiutano). La chat non avvia mai altro che `generateImage`.
- **Link in chat**: elenco CHIUSO di path (`app.chat.link-paths`, `entity-link-paths`); un nuovo path citabile va li' e in `appmap`.

### Training LoRA (`app.training`)
`/trainings`, `replicate/fast-flux-trainer`. NON e' un tool della chat e NON sta in `appmap`/`linkAppPaths`. Dipende da `generation` e `prompt`; nessuno dipende da `training`.
- **Dataset = bozza persistente lato SERVER** (`TrainingDataset`/`TrainingImage`, niente localStorage); concorrenza con `DatasetEditor#mutate`. Ritaglio solo client; didascalie via `IImageCaptioner` (guide `trainingCaption.*` SENZA `creative-context`), MANUAL mai sovrascritta se non da "Rigenera".
- **Lancio** (`TrainingService#start`, A PAGAMENTO, ordine fisso): `trainerVersion` → `requireHuggingFaceSupport` → zip e upload → repo HF → `ensureDestination` (modello privato NUOVO per lancio) → `createTraining` con **`RetryPolicy.NONE`**. Prima di spendere `check` (blocchi e avvisi `LaunchCheck`). Niente doppio lancio (lock a strisce per bozza). Il **token HF in chiaro** esiste SOLO dentro `start` (parte come `hf_token`, SEGRETO del trainer), mai salvato, loggato, in eventi o nel Model. Il corpo d'errore di `createTraining` e' redatto e la causa scartata.
- **Replicate IGNORA in silenzio i campi che lo schema non ha e puo' eseguire una versione diversa da quella richiesta** (il primo training vero giro' su una versione senza `hf_repo_id`/`hf_token`: nessun upload HF). Due controlli, nessuno basta: `trainerInputFields` prima di spendere; `TrainerJob#version` diversa e senza campi HF dopo = avviso, rimedio = caricamento a mano.
- **Avanzamento**: `ITrainings#refresh` con `TrainingLocks` (condiviso con cancel, delete, completamento). Solo `Kind.PERMANENT` fallisce il training; `app.training.timeout` (2h) annulla. Risultato (`TrainingCompletedEvent` UNA volta) → `ITrainingResults#complete`: tre passi indipendenti e idempotenti (preset in `/loras`, modello utilizzabile, verifica copia HF).
- **Upload a mano dei pesi su HF** (`ITrainingHfUploads`): token scelto AL CLIC (viaggia l'ID). I pesi sono in un tar PUBBLICO su replicate.delivery letto con **Range obbligatori** (200 al posto di 206 = errore, i pesi sarebbero corrotti). Protocollo HF (`HuggingFaceClient#uploadWeights`): preupload → batch LFS → PUT SENZA token → `verify` → commit NDJSON. Upload e commit con `RetryPolicy.NONE`; il PUT e' in streaming con `Content-Length` (lo storage firmato non accetta chunked). Dalla risposta del training si legge SOLO l'`output` (il record porta `hf_token` in `input`).
- Eliminare un training elimina snapshot e file, NON il modello Replicate, il repo HF ne' il preset. Eventi: `AppEventSource.TRAINING`/`HUGGINGFACE`; `TrainingBlobReferences` dichiara i blob per il backup.
- **Non verificato dal vero**: `GET /account`, secondo training su destination gia' addestrato, `basic` vs `multipart` HF, `verify`, `version` nella risposta di creazione, `hf_token` nei log. La prima prova reale dell'upload a mano va chiesta all'utente.

## Filosofia e cosa NON introdurre

Hypermedia-first, non SPA: server → HTML. Spring MVC + Thymeleaf; aggiornamenti parziali htmx; micro-interattivita' Alpine; componenti complessi = Web Component isolato. Zero build frontend (htmx, Alpine, Tailwind da CDN in `fragments/core/layout.html`).

- **WebFlux come modello del server**: no (resta `spring-boot-starter-webmvc`). Eccezioni, solo tipi Reactor: il client HTTP di Spring AI e `EventStreamController` (`GET /events`, `Flux<ServerSentEvent<?>>` da `Sinks.Many` in `PushService`, porta `IClientPushStream`).
- **React/Vue/Angular**: no. **Build step Tailwind obbligatorio**: no (opt-in `mvn -Ptailwind clean package`; config in `deep-flux/src/main/tailwind/tailwind.config.js`; il blocco `@layer base` e' duplicato fra `input.css` e `layout.html`: tenerli allineati; classi sempre stringhe letterali).
- Nessun CSS in `static/`. Immagini in `./data/images`, DB di sviluppo in `./data/postgres` (fuori da git).

## Stack

Spring Boot 4.x + MVC; Thymeleaf + layout dialect; htmx/Alpine/Pines UI via CDN; Spring Data JPA + PostgreSQL/pgvector; Flyway (`ddl-auto: validate`); `RestClient` verso Replicate; Spring AI 2.0.x (OpenRouter, embedding ONNX locali, `PgVectorStore`); `commonmark` + `commonmark-ext-gfm-tables` 0.24 (versione esplicita nel pom); ArchUnit.

## Architettura (core/app, esagoni)

Ogni sottosistema e' un esagono in `org.hexa.core.<s>` (generico) o `org.hexa.app.<s>` (specifico). Solo `app → core`; il core non conosce l'app. Fuori da `core`/`app` solo `Application` e `support` (test).

```
<core|app>/<s>/ domain | application | port/in (I<Capability>) | port/out (I<Thing>Store|Gateway|...) | adapter/in/<tech> | adapter/out/<tech>
```

- **Naming**: interfacce SEMPRE con prefisso `I` (porte comprese), implementazioni senza, col ruolo/backend (`IGenerations` → `GenerationService`, `IGenerationStore` → `JpaGenerationStore`). Repository Spring Data package-private in `adapter.out.persistence`.
- **Regole** (`ArchitectureTest` 14 regole + `SourceImportsTest`: una violazione si corregge nel codice, non allentando la regola): `domain` = JDK + `jakarta.persistence` + Hibernate types + kernel; `application` usa solo `port.out`/`port.in`, niente adapter/`org.springframework.web|http|ai|data|jdbc`/servlet/`java.sql`/IO file e immagini; `port` dipende solo da domain/port/kernel; `adapter.in` e `adapter.out` non si conoscono (eccezione `adapter.ai`); un controller parla solo con porte `in`. Fra sottosistemi si dipende SOLO da `port.in` e `domain`; nessun ciclo. `core.kernel`, `core.web`, `app.shared` sono condivisi e non dipendono da sottosistemi. **Nei commenti** citare classi di altri strati con `{@code Nome}`, mai `{@link}`.
- **Punti di estensione dell'app sul core** (elenco chiuso `ArchitectureTest.CORE_EXTENSION_POINTS`): `IEventLinkResolver`, `ITokenProviderCatalog`; SPI pubbliche in `port.in` (`HOST_SPIS`): `IBlobReferences`, `ISearchableSource`, `IChatToolkit`, `IChatTurnContributor`, `IChatPageContributor`, `IChatOutcomeResolver`, `ICreditSource`. Le inietta il SERVIZIO (`Optional<...>`).
- **Grafo**: core `ai` e `search` sono foglie; `chat` → `ai`, `search`, `manual`; `core.credits` foglia. App: `generation` → `core.ai`, `core.search`; `app.chat` → `generation`, `credits`, `core.chat`; `training` → `generation`, `core.ai`; `app.credits` → `generation`; `app.search` → `core.search`. `generation` NON conosce `chat` (solo `Generation.conversationId`, un `Long`); `core.search` non conosce `generation` ne' `chat` (legge i dati via `ISearchableSource`). `/deep-chat` e `/search` sono nel core con slot dell'host.
- **Autoconfigurazione**: `HexaCoreAutoConfiguration`/`HexaAiAutoConfiguration` (`META-INF/spring/...AutoConfiguration.imports`) scansionano i propri sottosistemi e li registrano come package JPA (`SubsystemPackagesRegistrar`): un host con qualunque package radice non scrive scan ne' `@EntityScan`. I package vi sono spezzati (`"org.hexa" + ".core."`) per non farli leggere a `SourceImportsTest`. Flyway: nessuna location, il default `classpath:db/migration` scansiona le sottocartelle.
- **Nuovo sottosistema/feature**: core o app? Disegnare PRIMA le porte (tipi di dominio o `core.kernel.Paged`, mai web/HTTP/Spring Data/Spring AI), poi gli adapter; se serve un dato di un altro sottosistema senza che ti conosca, definire una SPI nella tua `port.in`.
- **Config**: `application.yml` (solo app) importa `core.yml`, `ai.yml`, `prompts.properties`; chiavi disgiunte. **Un file IMPORTATO vince sull'`application.yml` che lo importa** (solo i profili `application-<profilo>.yml` vincono): una chiave di `core.yml`/`ai.yml` non si cambia dall'host; per questo in `ai.yml` stanno solo default stabili. Bundle `messages-core(.en)` + `messages-ai(.en)` + `messages(.en)` (`messages-ai` aggiunto da `AiMessagesConfig`). `core.yml` NON ha default per `DB_NAME`/`DB_USERNAME` (sono dell'app, nel `.env`). `app.layout.nav=top|sidebar` (`SidebarLayoutTests`). Punti di estensione del layout: `fragments/app/nav.html :: links`, `status-extras.html :: container`.
- **Barra in basso/crediti** (`ICredits`): il render NON fa chiamate remote, il chip carica `GET /credits/bar` via htmx. OpenRouter richiede una MANAGEMENT key (`OPENROUTER_MANAGEMENT_KEY`, senza il chip non compare). Replicate NON espone il saldo: stima = `replicate_balance_anchor` meno `IGenerations#totalCostSince`.

## Pattern Thymeleaf e controller

- **Due subtree di fragment, `app → core`** (`TemplateLayeringTest`): `fragments/core` = generico (kit UI, layout, feature infrastrutturali), conosce l'app solo via `fragments/app/nav` e `status-extras`; `fragments/app` = solo dominio. I fragment generici ricevono testi e URL gia' risolti come parametri (mai `#{app...}` ne' bean dell'app); i wrapper sottili dell'app li risolvono. In un parametro di fragment NON si usano `@bean`, `new`, `T(...)` ne' mappe SpEL: calcolarli con `th:with`.
- Ogni pagina: `layout:decorate="~{fragments/core/layout}"` sul proprio `<html>` (senza `lang` letterale) e contenuto in `<div layout:fragment="content">` (NON un `<main>`). `<title>` sostituisce quello del layout. Pagine in `templates/{app,core}/`.
- **Stessa URL, due risposte** distinte da `HX-Request` (fragment/pagina intera). Un fragment con parametri restituito come vista diretta richiede parametri **nominati**; la forma posizionale vale solo in `th:replace`.
- **Breadcrumbs** su OGNI pagina tranne la Home: `fragments/core/breadcrumbs :: trail(group, parentPath, parentText, current)` nello slot `breadcrumbs`, parametri nominati e tutti passati (inutilizzati `null`), `parentPath` grezzo (`everyPageButHomeShowsBreadcrumbs`).
- **Menu**: `header.menu.manage` in `messages-core`, `header.menu.create` nell'app.
- `generate-form.html`: `promptField` viene risostituito in outerHTML da `enhance-prompt`.

## Convenzioni

- **URL dell'app**: ogni attributo con un URL (`href`, `src`, `action`, `hx-*`, `connect`) passa SEMPRE da `@{...}` (reverse proxy su subpath, `X-Forwarded-Prefix`): `th:hx-post="@{/x}"`; fuori da htmx `th:attr` con `@{...}` in `|...|`. Lato Java `request.getContextPath() + "/gallery"`; `redirect:` no.
- **Theming**: solo Tailwind, utility inline; mai colori hardcoded fuori da `theme.extend.colors`; token `{ DEFAULT, dark }` (`canvas|surface|ink|ink-muted|line|accent|accent-contrast|danger|favourite|warning`) come `bg-canvas dark:bg-canvas-dark`. Dark mode `['selector', '[data-theme="dark"]']`; lo script di boot in `<head>` non si sposta (FOUC). `@layer base` solo per elementi "nudi"; mai nuove regole `@layer`.
- **Bottoni**: mai `<button>` a mano; fragment di `fragments/core/button.html`, `core/field-buttons.html` o `fragments/app/button-gen.html` (aggiungerne uno se nessuno calza), parametri nominati e TUTTI passati.
- **Alpine**: i binding non passano da `th:attr`/`#{...}`: valore dinamico in un `data-*` e `$el.dataset`.
- **Componenti Pines**: l'elenco di quelli supportati (select, accordion, popover, video, lightbox, paginazione, modal, slideover, dropdown) e' in `manual/it/02-architettura/03-web-htmx-thymeleaf.md` ("Componenti Pines supportati"): un nuovo componente = fragment in `fragments/core`, script in `layout.html`, riga in quella tabella.
- **Select**: mai `<select>` nuda; wrapper `pinesSelect` (`fragments/core/select.html`), la `<select>` nativa resta fonte di verita'; un cambio da codice si annuncia con `select.dispatchEvent(new Event('pines-select:sync'))` (`everySelectIsWrappedByThePinesSelectComponent`).
- **i18n**: ogni testo utente-visibile da `MessageSource` + `#{...}`; lingua da `Accept-Language`, default italiano. Bundle `messages-core*`/`messages-ai*`/`messages*` con chiavi **DISGIUNTE** (una chiave nuova nel bundle del lato del codice che la usa, in ENTRAMBE le lingue). Chiavi `<pagina-o-componente>.<categoria>.<elemento>`. Il testo del core NON nomina servizi dell'app. **Apostrofi** raddoppiati (`''`) nei messaggi con argomenti; argomenti numerici `{0,number,#}`. Java: errori all'utente con `core.kernel.i18n.Messages`, risolti al call site prima dell'eccezione.

### Errori, eventi di sistema, remoti
- Ogni chiamata a Replicate/OpenRouter/SearXNG e ogni errore interno passa da `ISystemEvents#record(operation, throwable[, subject])`: MAI un `catch` che ingoia o solo logga. `record` non lancia e consegna il toast alla prima occorrenza di una serie (5 min). Avvisi: `warn(source, operation, subject, message)` (messaggio gia' tradotto). Registra chi gestisce/ingoia l'eccezione (servizi in background, tool, watcher); se risale a un controller, il controller.
- **Toast**: header `HX-Trigger` via `HtmxEvents#addToastHeader`/`addHxTrigger`. Nessun bottone "Riprova" sul toast (rieseguire una POST creerebbe una seconda prediction a pagamento).
- **Overlay "operazione in corso"** blocca la UI durante i non-GET htmx; `data-busy="off|on"`, `data-busy-text`, `data-busy-delay`; watchdog `htmx.config.timeout` 180 s; fuori scope i turni di `/deep-chat`.
- **Nessuno stato indefinito**: `refresh` non lascia stati parziali (errore → `FAILED` + file ripuliti); `GenerationRecoveryService` all'avvio; sweep ogni `app.recovery.sweep-interval` (`app.recovery.enabled=false` nei test). Se `create` non salva la riga dopo la prediction, la annulla.
- **Chat**: se l'LLM fallisce `SpringAiAssistant` lancia `AssistantException` e `ChatService#reply` scrive un turno ASSISTANT d'errore (`ChatMessage.error`, mai rimandato all'LLM) e lancia `DeepChatFailedException` (gia' registrata).
- **HTTP**: `spring.http.clients.*` vale per tutti i `RestClient.Builder` auto-configurati; un nuovo client usa il builder iniettato, mai `RestClient.create()`.
- **`core.kernel.remote`**: `RemoteServiceException` (radice delle eccezioni dei servizi) porta `source()` e `kind()`: `TRANSIENT`, `PERMANENT`, `CONFIGURATION`, `REJECTED` (atteso, non si registra). Il resolver risponde 502 / 422 (toast, niente evento) / 500. `RemoteCaller#call` ritenta solo i `TRANSIENT`; **`RetryPolicy.NONE` ESPLICITA per le operazioni NON idempotenti/a pagamento** (`createPrediction`). `RestClientTranslator` = unica regola stato HTTP → `Kind`. `ReplicateException(String)` = `REJECTED`: un errore vero senza causa va costruito con `Kind` esplicito. **Nuovo servizio remoto**: valore in `*EventSource` + `events.source.<X>` nelle due lingue; `FooException extends RemoteServiceException`; client `extends RestRemoteClient` che implementa la `port.out`, ogni chiamata in `remote.call(...)`.

## Storage dei binari

Tutto cio' che l'app serve come file passa da `IImageStorageService` (nessun accesso diretto a filesystem/WebDAV, nessun resource handler statico: `/images/**` e' di `ImageController`, Range + ETag). Backend `storage.type=local|webdav`. Lancia SOLO `StorageException`. La porta espone `UploadedFile`, non `MultipartFile`.
- **Nomi**: ogni binario nuovo = `StorageNames#newFilename` (`<sha256 di 32 byte casuali>.<ext>`), mai derivato da id/URL/nome originale, nessuna dedup. Layout `ab/cd/<filename>` (`shardPath`); i vecchi file piatti non sono piu' serviti.
- **WebDAV**: contenuti SEMPRE cifrati AES-256-GCM a chunk (`ChunkedAesGcmCipher`) con `storage.webdav.encryption-key` (base64 di 32 byte, mai nel repo; persa = binari irrecuperabili). PUT su `.part` + MOVE; le scritture ritentano i soli transitori, le letture per `/images/**` NO. Cache `EncryptedBlobCache` (blob CIFRATI).
- **Migrazione locale → WebDAV** (`LocalToWebDavMigrator`): una tantum, `storage.migration.from-local.enabled=true`, riavviabile; a fine giro rimettere `enabled=false`.

## Migrazioni (Flyway)

Ogni modifica alla persistenza = **nuova migrazione SQL PostgreSQL** in `db/migration/{core,ai,app}`, nome `V<AAAA>_<MM>_<GG>_<HHMM>__<descrizione>.sql`, SEMPRE successiva a tutte le esistenti di tutte le location (`outOfOrder=false`); mai modificare un file eseguito (il checksum fa fallire l'avvio). **Nessuna FK dal core all'app**; le colonne di collegamento fra feature sono `Long` (mai `@ManyToOne` verso un altro esagono); la FK c'e' solo nella direzione delle dipendenze. Le baseline vincolano l'ordine: core `1200`, ai `1210` (tabelle `chat_*`, `vector_store`, `CREATE EXTENSION vector`), app `1220` (re-baseline 2026-10-07: i DB precedenti vanno ricreati). `generation.conversation_id` NON ha FK. Dialetto: identificatori minuscoli non quotati, testo lungo `text` + `@JdbcTypeCode(SqlTypes.LONGVARCHAR)` (MAI `@Lob`), `timestamptz`, `bytea`, enum Java = `varchar` senza ENUM/CHECK. Un modello nuovo = `INSERT` in `replicate_model`. In sviluppo: `docker compose down && rm -rf data/postgres`.

## Token API e segreti

CRUD in `/tokens` (`core.tokens`; `ApiToken.provider` e' una stringa, i servizi li elenca l'app via `ITokenProviderCatalog`). Dopo il salvataggio solo gli ultimi 4 caratteri; mai il segreto in log, eventi, toast o Model. Cifratura `ISecretCipher` con la STESSA chiave dei binari WebDAV (`app.secrets.encryption-key`); con `storage.type=local` senza chiave `isConfigured()` e' falso e salvare e' `CONFIGURATION`. Chiave persa = token irrecuperabili. Scaduto/inesistente alla generazione = `REJECTED`, nessuna prediction.

## Stato delle form di generazione (client)

Modello + parametri + prompt in `/generations/new` sono una preferenza del browser (`localStorage`), cambiano SOLO per modifica dell'utente o "Reimposta", mai per navigazione. Un solo script `generation-settings-persist.html :: script` (incluso DOPO il markup) su ogni `form[data-persist-key]`; `data-*`: `data-persist-key`, `-no-restore`, `data-shared-accept`, `data-persist-ignore` (mai scritti: `version`, un hash pinnato ripristinato di nascosto userebbe il modello sbagliato a pagamento). Non persistiti: file e hidden. Un nuovo form-type non richiede nulla qui.
- **Form per conversazione**: configurazione sul server (`generation_settings_json`, JSON GREZZO; `NULL` = precedente alla migrazione, `"{}"` = default); salva SOLO dopo `generation-settings:restored` con `fetch` keepalive (NON htmx: l'overlay), max 16 KB, senza `touch()`.
- **Slot globale prompt/seed** (`localStorage['generation.shared']`): "Usa prompt/seed" lo riempiono, `applySharedSlot` lo CONSUMA; la chiave e' duplicata in due script, tenerle allineate. Seed per file = `reusableSeedOf`; un seed di batch riproduce SOLO la prima immagine.
- **Campi numerici**: OGNI `<input type="number">` dei `generation-params-<form-type>.html` passa da `generation-params-number.html :: field(...)` (`NumericResetFieldsTests`); `num_outputs` max `IGenerationForms.MAX_NUM_OUTPUTS` (4) sia nel fragment sia in `asNumOutputs`. Seed: `generation-params-seed.html :: field`.
- **"Usa configurazione"** (`reuseConfig`, "Rigenera"): il SERVER compila il form (`GenerationController#reuseForm`) da dati salvati. Un campo di un fragment `generation-params-<form-type>.html` DEVE stare fra i `defaultFields()` del suo handler (altrimenti sparisce in silenzio: `GenerationFormFieldsCompletenessTest`). La `version` non si spinge MAI. Non riproducibili: maschera, upload sorgente, sorgente sparita, modello disattivato.

## Ricerca semantica (`core.search`; pagina `/search` = `app.search`)

Stesso Postgres. Spring AI (`Document`/`Filter`/`VectorStore`) non esce dall'adapter `adapter.out.vector` (`IVectorIndex` → `PgVectorIndex`).
- **Embedding** `multilingual-e5-small` quantizzato (384 dim, ~120 MB in `./data/models`); i prefissi `passage: `/`query: ` li applica `E5PrefixEmbeddingModel`. Punteggi compressi (0.7-0.9): top-K, non soglie fisse. `vector_store` da Flyway (`initializeSchema=false`), **nessun indice ANN** di proposito (un indice approssimato tronca a `ef_search`). Filtro `DocumentFilter` → jsonpath: EQ, NE, IN, NIN, AND, OR, GT/GTE/LT/LTE (NON NOT/ISNULL).
- UN documento per generazione riuscita (`GenerationSearchSource`); testo = prompt + (dopo `TAGS_SEPARATOR`) tag d'indice (UI e `ArchiveSearchTool` mostrano `visibleText`). Metadata `type` (`generation|imported|chat|conversation|note`), `refId`, ecc.; chiavi **riservate** di `VectorIndexer`: `contentHash`, `embeddingModel`, `indexedAt`.
- `ArchiveIndexService`: riconciliazione idempotente su tutti i bean `ISearchableSource`, in background e dopo gli eventi di generazione (`@TransactionalEventListener(fallbackExecution = true)`); rimuove i documenti dei `types()` la cui riga non esiste piu'. Una nuova fonte = un nuovo `ISearchableSource` nel suo sottosistema.
- `app.search.enabled=false` (test) spegne indice, scheduler, tool ed `EmbeddingModel` (usa `FakeEmbeddingModel`). Prove reali: `mvn test -Dtest='E5ModelSmokeTest,SemanticSearchWiringTest' -Dsemantic.model.test=true`.
- UI: UN form e UNA lista paginata (`GET /search/results`); testo = classifica sopra soglia (default 80%), senza testo = piu' recenti. Solo le note (`type=note`) sono modificabili (`IArchiveNotes`), i derivati in sola lettura. Slot dell'host in `app.search.host-fragment`. Nei test i documenti derivati finti possono essere cancellati dalla riconciliazione: per liste lunghe usare `type=note`.

## Backup e restore

`java -jar app.jar export <file> [--no-encrypt]` / `import <file> [--replace]` (esito 0/1/2). Esagono `core.backup` generico; parte da `Application.main` col profilo `backup` (`WebApplicationType.NONE`, un `ApplicationRunner` esegue ed ESCE, i listener "all'avvio" non partono); il profilo spegne recovery, search, token check, migrazione, cache. Tutti i bean tranne `IBlobReferences` sono `@Profile("backup")` (`BackupProfileContextTest`).
- **Formato**: zip in streaming (`manifest.json`, `blobs/`, `db/<tabella>.copy`, `summary.json`; DEFLATED sempre), cifrato = l'INTERO zip in formato `DFX1`. DB via `COPY` con `CopyManager` (non `pg_dump`), TUTTE le tabelle tranne `flyway_schema_history`, `vector_store` compresa (**le note manuali vivono SOLO li**); export in UNA transazione REPEATABLE READ; ordine topologico sulle FK (`TableOrder`).
- **Binari**: quelli REFERENZIATI dal DB (SPI `IBlobReferences`; `BackupBlobColumnsTest` fa fallire una colonna `%filename%` non dichiarata). Letti/scritti da `IImageStorageService`.
- **Import**: controlli in sola lettura → **binari prima** → **DB per ultimo**, in transazione (Flyway `migrate` fino alla versione del backup, `TRUNCATE ... RESTART IDENTITY CASCADE`, `COPY`, `setval` di OGNI identity, verifica righe) → `migrate` all'ultima. Un backup vecchio entra in un jar nuovo, non viceversa. Se il caricamento fallisce il DB resta migrato ma vuoto: rilanciare con `--replace`.
- **Credenziali**: solo `DB_*` e, con WebDAV, `STORAGE_WEBDAV_*`; mai `REPLICATE_*`, `OPENROUTER_*`, `SEARXNG_*`. `backup.encryption-key` = `${BACKUP_ENCRYPTION_KEY:${storage.webdav.encryption-key:}}`: export senza chiave RIFIUTA (serve `--no-encrypt`); la chiave NON e' nel backup. `api_token.token_encrypted` si copia com'e': con un'altra chiave non si apre (avviso a fine import).

## Manuale online (`core.manual`)

Markdown in `deep-flux/src/main/resources/manual/<lingua>/<NN-gruppo>/<NN-pagina>.md` (Uso per l'utente, Architettura per chi sviluppa), servito da `/manual`; il bot lo consulta con `ManualTool` (solo gruppo `uso`). **Slug = nome file senza prefisso, unico fra tutti i gruppi**; etichetta del gruppo `manual.group.<gruppo>` nel bundle dell'APP; titolo = primo `# `; una lingua senza cartella ricade su `it`; contenuto con gli accenti veri.
- Una **sezione** (da `#`/`##` al successivo) e' l'unita' cercata e citata; **ancore** da UNA sola `HeadingSlugs` (i titoli non possono avere formattazione inline). Link nei `.md`: fra pagine il nome VERO del file (`../01-uso/03-x.md#ancora`), verso l'app un path radice (`/gallery`), `#ancora`, `http(s)://`.
- `CommonmarkRenderer` con `escapeHtml(true)` e `sanitizeUrls(true)`; `manual.html` usa `th:utext` solo sui due `<div>` dell'articolo; niente `@layer`/Typography (varianti arbitrarie Tailwind definite UNA volta con `th:with="prose=..."`). Test: `ManualContentTest` (slug unici, un solo `#`, sezioni <= 6.000 car., link e ancore esistenti), `ManualControllerTest`. Con Maven 3.8.1 su JDK 24 il TLS del mirror puo' fallire: `-Dhttps.protocols=TLSv1.2,TLSv1.3`.

## Comandi e test

```bash
docker compose up -d                # PostgreSQL+pgvector di sviluppo (DB_* nel .env, vedi .env.example)
mvn -q install -DskipTests && mvn -pl deep-flux spring-boot:run   # sviluppo
mvn test                            # test (richiede Docker)
mvn test -pl deep-flux -am -Dtest=ArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false   # solo architettura (senza Docker)
mvn clean package                   # jar in deep-flux/target; -Ptailwind per il CSS compilato
java -jar deep-flux/target/deep-flux-*.jar export backup.dfb
```

`mvn test` non tocca mai il DB di sviluppo: `PostgresTestContainerInitializer` avvia UN container `pgvector/pgvector:pg17` e Flyway applica lo schema reale. Surefire fissa `storage.type=local`, la migrazione spenta e una chiave di test (battono il `.env`); un test WebDAV li sovrascrive con `@SpringBootTest(properties=...)`, mai contro il server vero. I test condividono il DB: i `@SpringBootTest` che scrivono ripuliscono a mano o sono `@Transactional`. `TemplateRenderingTests` sta in `controller`. **Mai chiamate vere a Replicate/OpenRouter/HF nei test**: `@MockitoBean` per `IPredictionGateway`, `ITrainerGateway`, `IHuggingFaceRepos`, `IImageCaptioner`, `ICaptionJobs`, `ITrainingResults`, `IImageDescriber`; `MockRestServiceServer` per i client; con uno stub gia' lanciante ri-stubbare con `doReturn(...).when(mock)`.

## Checklist per una nuova pagina/feature

1. Navigazione → controller in `adapter/in/web` (solo porte `in`) + template col layout manager; voce in `nav.html` e breadcrumbs.
2. Aggiornamento parziale → fragment in `fragments/app/` (o `core/`), restituito se `HX-Request`.
3. Interattivita' locale → Alpine; componente complesso stateful → prima un Web Component isolato.
4. Tocca un'entity → migrazione Flyway nella location giusta; nessuna FK core → app.
5. Testo visibile → chiave nel bundle giusto in entrambe le lingue.
6. `<button>` → fragment; `<select>` → `pinesSelect`.
7. Evento che l'utente deve notare → `ISystemEvents#warn`/`#record`.
8. Nuovo sottosistema/porta/dipendenza → `ArchitectureTest` verde.
9. Cambia cio' che l'utente vede o fa → aggiornare il manuale (`manual/it/01-uso/`, o `02-architettura/`): `ManualContentTest` blocca solo i link morti, non il testo vecchio. Etichette come nei bundle con gli accenti veri; per icone e pulsanti il titolo che l'utente vede (si controlla nel template).
