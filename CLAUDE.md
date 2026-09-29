# CLAUDE.md

Guida di riferimento per lavorare su questo repository. Leggerla prima di
aggiungere pagine, endpoint o dipendenze: le scelte qui sotto non sono
casuali, sono vincoli deliberati per mantenere il progetto snello.

## Scopo

L'applicazione serve a:

1. **Generare immagini con l'ausilio di un chatbot** — `/deep-chat`:
   descrivi cosa vuoi, l'assistente puo' cercare sul web per informarsi
   (`WebSearchTool`, via SearXNG) e generare l'immagine su Replicate
   (`ImageGenerationTool`) sempre col modello scelto nel combobox
   (`ImageGenerationTool.MODEL_CONTEXT_KEY`, via `ToolContext`, non un
   parametro che l'LLM sceglie componendo la chiamata al tool — per ora
   la scelta del modello resta interamente lato UI). `/generations/new`
   resta la via
   diretta (form, senza chatbot) per chi vuole specificare modello/
   parametri a mano. `ImageGenerationTool` avvia la generazione e torna
   subito, senza attenderne l'esito: il polling verso Replicate continua
   in background (`DeepChatGenerationWatcher`, `@Async`) e il risultato
   arriva in un secondo momento come nuovo turno della conversazione,
   pushato via SSE (`GET /events`, `GenerationEventBroadcaster`) a chi ha
   quella conversazione aperta — nessun polling client-side per la chat.
   Finche' una generazione e' in corso, sia in chat sia su `/generations/{id}`
   compare un placeholder (`fragments/generation-placeholder.html`, immagine
   dummy + bottone "Interrompi", stili INLINE perche' finisce anche nello
   shadow DOM di `<deep-chat>`) che chiede conferma e interrompe la
   prediction su Replicate (`POST /generations/{id}/cancel`,
   `GenerationService#cancel`): esito `FAILED` "annullata"; se il cancel
   fallisce il bottone si disabilita e si attende la fine naturale. In chat
   i placeholder viaggiano nella risposta del turno (`generationIds`) e sono
   ripristinati al reload via `Generation.conversationId` (migrazione V11). A generazione finita il
   risultato rimpiazza il placeholder nello stesso messaggio `html` (`fragments/generation-result.html`,
   stili inline) con un bottone Nascondi/Mostra immagine (`button :: galleryToggle`, handler `gen-toggle`
   in `deep-chat.html`); anche la cronologia ricaricata usa lo stesso markup.
   `/generations/{id}` (form diretto) resta invece a polling client-side
   htmx ogni 2s mentre la generazione non e' terminale, invariato; a
   stato terminale quella stessa pagina (`fragments/generation.html ::
   status`) *e'* anche il dettaglio della generazione (prompt/modello/
   seed/parametri, tutte le immagini — anche piu' di una, se
   `num_outputs > 1` — in una griglia con lightbox (zoom, next/prev),
   cancellabili singolarmente, vedi `fragments/generation-images.html`,
   cancellazione dell'intera generazione) — niente pagina di dettaglio
   separata.
2. **Indicizzare le immagini generate e renderle reperibili/visualizzabili
   tramite un archivio** — ogni generazione (chatbot o form diretto)
   diventa una riga `Generation`. `/gallery` resta l'archivio delle sole
   generazioni completate con successo (paginato, con cancellazione in
   blocco dalla griglia), che si aggiorna da solo (SSE, vedi punto 1
   sopra) quando una qualunque generazione completa; le sue card linkano
   al dettaglio su `/generations/{id}` (punto 1 sopra), non hanno una
   pagina di dettaglio propria. `/generations` (senza id) e' invece il
   listato paginato di TUTTE le generazioni, qualunque stato — selezione
   multipla (anche via shift-click), cancellazione della selezione o
   dell'intero archivio in un colpo solo (con conferma testuale
   rinforzata, vedi `generations-list.html`). La cancellazione di una
   generazione elimina anche i suoi file immagine; cancellare l'ultima
   immagine rimasta di una generazione elimina a cascata la generazione
   stessa (`GenerationService#deleteImage`).
3. **Mantenere una storia delle conversazioni e poterle riprendere in
   futuro** — `/deep-chat` supporta piu' conversazioni, ognuna una riga
   `ChatConversation` (migrazione V5) che raggruppa i propri turni
   (`ChatMessage`/`ChatMessageRepository`, migrazione V3). Una colonna
   sinistra a larghezza fissa ospita, sopra un bottone "Nuova
   conversazione" sempre visibile (`POST /deep-chat/new`,
   `ChatConversationService`), un rail NON collassabile
   (`fragments/accordion.html :: staticPanels`) con due sezioni sempre
   entrambe visibili: la lista delle conversazioni esistenti
   (`fragments/conversation-list.html`, piu' di recente attiva prima —
   selezionarne una ricarica la cronologia completa, comprese le
   immagini; rinomina/cancellazione inline) e il pannello impostazioni
   di generazione (`fragments/generation-params.html`).
   Resta non multi-utente (come il resto dell'app: `Generation` non ha
   un owner), solo multi-conversazione per lo stesso singolo utente.
   Sotto la chat, un secondo accordion — questo collassabile (Pines UI,
   `fragments/accordion.html :: panels`) — ospita una galleria
   "contestuale" (`fragments/gallery.html :: grid`, riusata cosi' com'e')
   con le sole immagini generate in quella conversazione — la galleria
   globale in `/gallery` (punto 2 sopra) resta invariata, indipendente
   dalle conversazioni.

Ulteriori evoluzioni seguiranno, ma sempre pertinenti a questi tre punti:
non aggiungere feature (pagine demo, integrazioni, pattern) che non
servono direttamente a generare, archiviare o conversare sulle immagini.
Se un domani serve dimostrare un pattern htmx/Alpine non ancora coperto
dal codice reale, farlo aggiungendolo a una feature vera, non con una
pagina demo isolata (le pagine demo starter — "Load more", "Search",
chat di rifinitura prompt — sono state rimosse per questo). L'icona
"AI enhance" di `/generations/new` (vedi punto 1 sopra,
`PromptEnhancementService`) non e' una riedizione di quella chat di
rifinitura rimossa: non e' una pagina/conversazione a se stante ma
un'azione puntuale sulla form reale di generazione, che riscrive il
prompt gia' inserito senza aprire un'interfaccia propria.

## Filosofia

Hypermedia-first, non SPA. Il server resta la fonte di verità dello stato
dell'applicazione e restituisce HTML, non JSON. Il client arricchisce
quell'HTML, non lo sostituisce.

- **Navigazione** → HTML renderizzato dal server (Spring MVC + Thymeleaf).
- **Aggiornamenti parziali / navigazione senza reload** → htmx.
- **Micro-interattività locale** (toggle, dropdown, form state, validazione
  immediata) → Alpine.js.
- **Componenti davvero complessi** (editor, diff viewer, grafici) → se e
  quando servono, un Web Component isolato montato su un singolo `<div>`,
  non un framework SPA per l'intera app.
- **Theming** → Tailwind (Play CDN) e' il sistema di stile dell'intero
  sito, non solo dei componenti Pines UI: nessun `theme.css`, classi
  utility inline nei template. Il tema chiaro/scuro/auto resta lo stesso
  toggle Alpine su `data-theme` di sempre (localStorage +
  `prefers-color-scheme`); a cambiare e' solo il meccanismo CSS che lo
  consuma — `darkMode: ['selector', '[data-theme="dark"]']` in
  `tailwind.config`, non `prefers-color-scheme` diretto. Vedi
  "Convenzione: theming" sotto.

Zero step di build frontend: niente npm/webpack/vite/esbuild. htmx,
Alpine.js e Tailwind (Play CDN, vedi sotto) sono caricati da CDN in
`fragments/layout.html`. Se un giorno serve vendorizzarli offline, basta
scaricare i file JS in `static/js/` e cambiare i `<script src="...">` —
nessun altro impatto.

### Cosa NON introdurre senza una ragione concreta

- **Spring WebFlux come modello di programmazione del server** — nessun
  beneficio reale per una webapp a navigazione prevalentemente
  server-rendered; aggiunge solo complessità. Restare su Spring MVC
  classico (`spring-boot-starter-webmvc`), un solo server (Tomcat) per
  tutta l'app — niente `spring-boot-starter-webflux`, mai (Spring Boot
  sceglie UN application-type per l'intera app dal classpath: aggiungere
  quello starter accanto a webmvc non darebbe comunque una singola rotta
  reattiva "mescolata" alle altre, servirebbe un secondo server embedded
  su una porta separata, o la migrazione dell'intera app — nessuna delle
  due è mai stata necessaria finora). Due eccezioni isolate e deliberate,
  entrambe **tipi Reactor**, non il modello WebFlux:
  1. lo starter Spring AI (vedi Stack sotto) porta Reactor/WebFlux in
     classpath per il *client* HTTP verso i provider LLM, non per servire
     richieste;
  2. `EventStreamController` (`GET /events`, vedi Scopo punto 1/3) ritorna
     un `Flux<ServerSentEvent<?>>` (sorgente in `GenerationEventBroadcaster`,
     un `Sinks.Many`): supportato nativamente da `spring-webmvc` dalla 5.0
     (`ReactiveTypeHandler`), gira sullo stesso Tomcat/`DispatcherServlet`
     di ogni altro controller — non introduce ne' un secondo server ne'
     `spring-boot-starter-webflux`, solo i tipi Reactor (gia' in classpath
     per il punto 1) al posto di un registro di `SseEmitter` scritto a
     mano.
  Nessuna delle due è un'apertura generale a WebFlux nel resto del
  progetto: un controller che ritorna un tipo reattivo per il gusto di
  farlo (senza un bisogno concreto di streaming, come qui il push SSE)
  resta fuori scope.
- **React/Vue/Angular come framework applicativo** — duplicherebbe la
  gestione di routing/stato che il server già fa. Se serve un widget
  isolato, montarlo come Web Component su un `<div>` mirato, non riscrivere
  la navigazione.
- **Un build step Tailwind (CLI/PostCSS)** — scelta deliberata e
  invertita rispetto alla precedente ("Tailwind solo per Pines UI, resto
  del sito su `theme.css`"): oggi Tailwind e' il sistema di stile
  dell'intero sito (vedi Theming sopra e "Convenzione: theming" sotto),
  ma resta interamente sul Play CDN (JIT nel browser) — nessun
  `tailwind.config.js` su disco, nessun npm/PostCSS. La configurazione
  (`darkMode`, `theme.extend.colors`/`fontFamily`) vive nello `<script>`
  inline di `fragments/layout.html`, il layer di stili trasversali
  (`@layer base`) in un `<style type="text/tailwindcss">` nello stesso
  file — entrambi meccanismi nativi del Play CDN, non un secondo sistema
  di build parallelo.

## Stack

| Livello | Scelta | Perché |
|---|---|---|
| Backend | Spring Boot 4.x, Spring MVC | Coerente con lo stack Spring esistente, nessun context-switch |
| Template engine | Thymeleaf | Fragment nativi, integrazione naturale con Spring MVC |
| Layout manager | thymeleaf-layout-dialect | `layout:decorate`/`layout:fragment` al posto di fragment parametrizzati scritti a mano: la BOM di Spring Boot ne gestisce la versione, nessuna dipendenza aggiuntiva da tracciare |
| Navigazione parziale | htmx (via CDN) | Markup dichiarativo via attributi, niente build |
| Micro-interattività | Alpine.js (via CDN) | Stato dichiarato inline, niente build |
| Componenti UI pronti | Pines UI (devdojo.com/pines) + Tailwind Play CDN | Componenti Alpine.js gia' scritti (dropdown, modali, tabs...) da copiare cosi' come sono; usano classi Tailwind, che oggi e' il sistema di stile di tutto il sito (non solo di questi componenti, vedi riga Theming) — `preflight` e' quindi attivo, i componenti Pines condividono la stessa base di stile del resto del sito, non piu' isolati da essa |
| Theming | Tailwind (Play CDN), `theme.extend.colors`/`dark:` variant | Nessun CSS scritto a mano: design token nella config inline di `fragments/layout.html`, cambio tema = cambio attributo `data-theme` (letto da `darkMode` custom selector), zero ricalcolo server |
| Persistenza | Spring Data JPA + H2 file-based | Metadata delle generazioni (prompt/parametri/stato/file immagine); DB embedded su file locale, zero server esterno |
| Migrazioni schema DB | Flyway (`spring-boot-starter-flyway`) | Lo schema e' versionato in SQL esplicito, non dedotto da Hibernate (`ddl-auto: validate`): ogni modifica al DB e' una migrazione tracciabile, riproducibile, mai un'alterazione implicita a runtime |
| Client HTTP verso Replicate | `RestClient` (`spring-boot-starter-restclient`) | Sincrono, nessuna dipendenza WebFlux/reactor per questo client |
| Assistente chat (OpenRouter) | Spring AI (`spring-ai-starter-model-openai`) via `ChatClient`, `base-url` puntato su `https://openrouter.ai/api/v1` | OpenRouter espone un'API OpenAI-compatibile: nessun client HTTP custom da scrivere/mantenere. Richiede Spring Boot 4.x (Spring AI 2.0.x) — vedi eccezione WebFlux sopra |
| Build | Maven | — |
| Java | 21 | LTS |

Il package radice del codice applicativo è `org.dual.replicate`.

## Struttura del progetto

```
src/main/java/org/dual/replicate/
  Application.java              # entry point Spring Boot
  controller/
    HomeController.java         # pagina intera, esempio minimo
    GenerationController.java   # crea una generazione, polling htmx dello stato + dettaglio a stato terminale, listato paginato /generations (qualunque stato), cancellazione (singola/selezione/per-immagine/intero archivio), riscrittura del prompt via AI ("AI enhance", POST /generations/enhance-prompt)
    GalleryController.java      # galleria (load more): SOLO generazioni SUCCEEDED, cancellazione in blocco dalla griglia
    DeepChatController.java     # pagina <deep-chat> + lista conversazioni + galleria contestuale (tutte le route HTML sotto /deep-chat/*)
    DeepChatApiController.java  # endpoint JSON per <deep-chat> (non fragment HTML)
    EventStreamController.java  # GET /events (SSE): unico endpoint di push, vedi GenerationEventBroadcaster
  domain/
    Generation.java             # entity JPA: prompt, modello, parametri, seed, stato, file immagine
    GenerationStatus.java
    ChatConversation.java       # entity JPA: una conversazione di /deep-chat (titolo, elencabile/rinominabile/eliminabile dalla lista conversazioni)
    ChatMessage.java            # entity JPA: un turno persistito di /deep-chat, appartiene a una ChatConversation (ruolo, testo, immagine opzionale)
    ChatMessageRole.java
    ReplicateModel.java         # entity JPA: un modello Replicate censito (owner/name/version/formType), vedi migrazione V6
    GenerationFormType.java     # enum: quale form/handler di generazione usa un ReplicateModel (FLUX_LORA_FF3, FLUX_2_KLEIN_9B, FLUX_KREA_DEV)
  repository/
    GenerationRepository.java
    ChatConversationRepository.java
    ChatMessageRepository.java
    ReplicateModelRepository.java
  replicate/
    ReplicateClient.java        # wrapper RestClient sulle API Replicate
    PredictionResponse.java
    ReplicateException.java
    TooManyPredictionsException.java # rifiuto applicativo: troppe prediction gia' in corso PER LO STESSO MODELLO (vedi GenerationService#create)
    ReplicateModelCatalog.java  # legge il catalogo modelli censiti (ReplicateModelRepository) per il combobox di /generations/new e /deep-chat
  search/
    SearxngClient.java          # wrapper RestClient su un'istanza SearXNG (Basic Auth)
    SearxngResponse.java, SearchResult.java, SearxngException.java
  service/
    GenerationService.java      # crea la prediction, fa avanzare lo stato, orchestra il download; pubblica GenerationCompletedEvent a ogni transizione terminale
    GenerationCompletedEvent.java # evento di dominio: una Generation e' diventata terminale (successo o fallimento), qualunque sia il percorso che ce l'ha portata
    GenerationsDeletedEvent.java # evento di dominio: una o piu' Generation sono state eliminate (GenerationService#delete/#deleteAll/#deleteEverything), singola o in blocco
    GenerationImageDeletedEvent.java # evento di dominio: una singola immagine e' stata rimossa da una Generation ANCORA esistente (GenerationService#deleteImage, caso non a cascata)
    GenerationEventBroadcaster.java # sorgente Reactor (Sinks.Many/Flux) di GET /events, broadcast "gallery-update"/"chat-message"
    GenerationParameterHandler.java # interfaccia: costruisce l'input Replicate per un GenerationFormType a partire dai campi sottomessi, un'implementazione per form-type
    GenerationParameterHandlers.java # risolve il GenerationParameterHandler di un GenerationFormType (bean auto-raccolte), usato da GenerationController/DeepChatController/DeepChatApiController
    FluxLoraFf3ParameterHandler.java # GenerationParameterHandler di FLUX_LORA_FF3: i 9 campi tipizzati (width/height/formato/steps/guidance/seed/lora scale/variante flux/num output)
    Flux2Klein9bParameterHandler.java # GenerationParameterHandler di FLUX_2_KLEIN_9B: aspect_ratio/megapixels/seed/go_fast/formato/qualita' (schema reale del modello, vedi migrazione V7)
    FluxKreaDevParameterHandler.java # GenerationParameterHandler di FLUX_KREA_DEV: aspect_ratio/megapixels(2 sole opzioni)/seed/go_fast/guidance/num_outputs/formato/qualita'/steps (schema reale del modello, vedi migrazione V10)
    ImageStorageService.java    # scrive i file immagine su storage.images-dir
    PromptEnhancementService.java # riscrittura one-shot (senza tool ne' cronologia) di una bozza di prompt in un prompt Flux ben formato in inglese, per l'icona "AI enhance" di /generations/new - un ChatClient dedicato, senza defaultTools(...), non l'istanza di DeepChatService
    ChatConversationService.java # CRUD conversazioni di /deep-chat (crea/rinomina/elimina)
    DeepChatService.java        # orchestrazione del Web Component <deep-chat>, persiste la cronologia per conversazione; avvia i watch di background dopo ogni turno
    DeepChatGenerationWatcher.java # @Async: attende in background l'esito di una generazione avviata da /deep-chat, la persiste come nuovo turno e la notifica via SSE
    WebSearchTool.java          # tool Spring AI: ricerca web via SearxngClient
    ImageGenerationTool.java    # tool Spring AI: avvia una generazione via GenerationService e torna subito, senza attenderne l'esito
    GenerationResultHolder.java # canale d'uscita tool->DeepChatService (via ToolContext): gli id delle generazioni avviate nel turno, non piu' un risultato gia' pronto
  config/
    StorageConfig.java          # espone storage.images-dir come /images/**

src/main/resources/
  application.yml
  db/migration/
    V1__create_initial_schema.sql   # schema Flyway, vedi sezione dedicata sotto
    V2__drop_chat_tables.sql         # rimossa la persistenza della vecchia chat di rifinitura prompt
    V3__create_chat_message.sql      # persistenza della cronologia di /deep-chat (vedi Scopo, punto 3)
    V4__generation_multiple_images.sql # una Generation puo' avere piu' immagini (num_outputs > 1)
    V5__chat_conversations.sql       # CHAT_CONVERSATION + CONVERSATION_ID su CHAT_MESSAGE (vedi Scopo, punto 3)
    V6__create_replicate_model.sql   # tabella REPLICATE_MODEL (catalogo censito a mano) + seed di sdurz75/flux-lora-ff3
    V7__add_flux_2_klein_9b_model.sql # estende l'ENUM FORM_TYPE + seed di black-forest-labs/flux-2-klein-9b (VERSION NULL, shortcut "ultima versione")
    V8__add_generation_seed.sql      # colonna GENERATION.SEED (Long, nullable): il seed usato diventa un campo di prima classe, non piu' solo dentro PARAMETERS_JSON
    V11__generation_conversation.sql # colonna GENERATION.CONVERSATION_ID (nullable, FK ON DELETE SET NULL): conversazione che ha avviato la generazione, per ripristinare il placeholder al reload di /deep-chat
    V10__add_flux_krea_dev_model.sql # estende l'ENUM FORM_TYPE + seed di black-forest-labs/flux-krea-dev (VERSION NULL, shortcut "ultima versione")
  templates/
    index.html                   # home
    generate.html                 # form nuova generazione
    generation-status.html       # pagina di stato/polling di una generazione + dettaglio a stato terminale (prompt/parametri/immagini/cancellazione, vedi fragments/generation.html :: status)
    gallery.html                 # galleria (griglia paginata), SOLO generazioni SUCCEEDED
    generations-list.html        # listato paginato /generations, qualunque stato: selezione multipla (shift-click incluso), cancellazione selezione/intero archivio (modal con conferma testuale)
    deep-chat.html                # pagina che ospita <deep-chat> + rail sinistro non collassabile (lista conversazioni/impostazioni) + accordion collassabile (galleria contestuale)
    fragments/
      layout.html                # shell HTML condivisa (head, footer), decoratore layout-dialect, config Tailwind + @layer base
      header.html                # header di navigazione + theme switch, incluso da layout.html
      button.html                # fragment parametrici dei bottoni (primary/danger/themeToggle/aiEnhance), vedi "Convenzione: theming"
      alert.html                 # fragment error(text): box di errore/avviso, riusato da generate-form/generation/generation-params
      generate-form.html         # fragment del form (riusato anche per mostrare errori); promptField(prompt, enhanceError) e' il blocco label+textarea+icona "AI enhance", risostituito per intero (outerHTML) da POST /generations/enhance-prompt
      generation-params.html     # guscio: select modello (censiti in DB) + contenitore dei campi del form-type corrente, condiviso da generate-form.html e deep-chat.html
      generation-params-flux-lora-ff3.html # campi del form-type FLUX_LORA_FF3 (vedi GenerationFormType/FluxLoraFf3ParameterHandler), inclusi dal guscio sopra
      generation-params-flux-2-klein-9b.html # campi del form-type FLUX_2_KLEIN_9B (vedi GenerationFormType/Flux2Klein9bParameterHandler), incluso dallo stesso guscio
      generation-params-flux-krea-dev.html # campi del form-type FLUX_KREA_DEV (vedi GenerationFormType/FluxKreaDevParameterHandler), incluso dallo stesso guscio
      generation.html            # fragment status: polling di una generazione + dettaglio completo a stato terminale (prompt/modello/seed/parametri, immagini cancellabili, cancellazione generazione) - unica pagina di dettaglio, vedi CLAUDE.md
      generation-placeholder.html # placeholder(generationId, conversationId, generationsPage, cancelDisabled): immagine dummy + "Interrompi", stili inline (usato anche in <deep-chat>, vedi deep-chat.html)
      generation-result.html     # result: risultato di una generazione in /deep-chat (testo + toggle nascondi/mostra + immagini), template clonato dal client come il placeholder
      generation-images.html     # fragment grid(generation, conversationId, generationsPage): tutte le immagini di una Generation in una griglia con lightbox (zoom, next/prev) e cancellazione per-immagine (cascade sull'ultima), usato da fragments/generation.html
      gallery.html               # griglia + lightbox della galleria (content/grid), compone gallery-card e pagination; grid(...) riusata anche dalla galleria contestuale di /deep-chat
      gallery-card.html          # card di una singola generazione (link di dettaglio verso /generations/{id})
      generations.html           # fragment content/list del listato /generations: paginazione + selezione multipla (seleziona tutte, shift-click), compone generation-row e pagination
      generation-row.html        # riga di una generazione nel listato /generations (qualunque stato, non solo SUCCEEDED), checkbox di selezione + cancellazione singola
      description-list.html      # fragment term(text): <dt> di una lista di definizioni, usato dal dettaglio generazione (fragments/generation.html :: status)
      pagination.html            # paginazione generica (non specifica della galleria), riusata da /gallery e /generations
      accordion.html             # accordion Pines UI generico a N pannelli (labels/bodies accoppiate per indice): panels(...) collassabile (galleria contestuale di deep-chat.html), staticPanels(...) non collassabile, tutti i pannelli sempre visibili (rail sinistro di deep-chat.html: lista conversazioni + impostazioni)
      conversation-list.html     # contenuto della lista conversazioni di /deep-chat (elenco, rinomina, cancellazione), sezione del rail sinistro non collassabile
      live-events.html           # connessione SSE a GET /events, ri-dispatchata come CustomEvent su document.body; incluso solo da gallery.html, generations-list.html e deep-chat.html
```

Le immagini generate e il DB H2 vivono in `./data/` (fuori da git, vedi
`.gitignore`): sono stato applicativo prodotto a runtime, non asset del
progetto. Nessuna directory `static/`: lo stile e' interamente Tailwind
(vedi "Convenzione: theming"), niente CSS vendorizzato da servire.

## Pattern Thymeleaf: layout manager (thymeleaf-layout-dialect)

`fragments/layout.html` e' un template decoratore: ogni pagina lo applica
con `layout:decorate` sul proprio `<html>` e marca il blocco da inserire
con `layout:fragment="content"`:

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

Niente `lang` letterale sul proprio `<html>`: e' risolto dinamicamente
dal decoratore (vedi "Convenzione: internazionalizzazione" sopra),
ripeterlo qui lo vince nel merge del dialect e disattiva il meccanismo.

Comportamento di default del dialect, da tenere a mente:

- il `<title>` della pagina **sostituisce** quello di `layout.html`
  automaticamente — non serve marcarlo con `layout:fragment`;
- il resto di `<head>` viene **fuso** (unione, non sostituzione): un
  elemento aggiuntivo in `<head>` nella pagina finisce nell'head finale
  insieme a quelli di `layout.html`, senza doverlo dichiarare come
  fragment;
- un fragment della pagina **sostituisce l'elemento del decoratore tag
  incluso**, non solo il suo contenuto — per questo il fragment `content`
  nelle pagine e' un `<div>` come nel decoratore, non un `<main>`: il
  `<main>` unico che li contiene entrambi (`content` e il fragment
  opzionale `breadcrumbs`, vuoto se la pagina non lo definisce) vive solo
  in `layout.html`, con le utility Tailwind del contenitore fluido (vedi
  "Convenzione: theming"). Ripetere `<main>` nella pagina produrrebbe un
  `<main>` annidato (HTML non valido) e il padding raddoppiato.

Per creare una nuova pagina: copiare questo scheletro, non serve toccare
`layout.html`. Attenzione: il fragment `content` della pagina resta un
semplice `<div>`, **senza** le classi del contenitore — quelle vivono
solo sul `<main>` di `layout.html` (vedi punto sopra): ripeterle anche
nella pagina non avrebbe alcun effetto sul markup finale (`<main>` non
annidabile, quel `<div>` non lo sostituisce) ma
confonderebbe chi legge il template sull'origine del margine laterale.

## Pattern controller: quando restituire fragment vs pagina intera

Regola: **stessa URL, due risposte**, distinguendo in base all'header
`HX-Request` che htmx aggiunge automaticamente a ogni sua richiesta.

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

Attenzione se il fragment prende parametri (es. la paginazione della
galleria sotto): restituito come **vista di risposta diretta** da un
controller, Thymeleaf richiede parametri **nominati**, non posizionali
— `frag(nome=${valore})`, non `frag(${valore})`. La forma posizionale
funziona solo dentro un `th:replace` inline in un altro template (dove
la espressione la valuta il parser OGNL/SpringEL, non
`ThymeleafView.renderFragment`), altrimenti va in 500 con
`IllegalArgumentException: Parameters in a view specification must be
named`. Vedi `GalleryController` per l'uso corretto.

Vantaggi di questo pattern rispetto ad avere due endpoint separati:

- un solo URL, condivisibile/bookmarkabile, utilizzabile sia per la
  navigazione diretta del browser (prima apertura, refresh, link
  condiviso) sia per lo swap htmx;
- nessuna duplicazione di markup: il fragment dei risultati è lo stesso
  sia che venga incorporato nella pagina intera sia che venga restituito
  da solo.

Esempi già implementati da copiare:

- `GenerationController` — polling di stato, fragment senza parametri.
- `GalleryController` — paginazione numerata, i link sostituiscono
  l'intero contenuto con `hx-target="#gallery-content"` +
  `hx-swap="innerHTML"` (vedi `fragments/gallery.html`); il fragment
  prende parametri nominati, e' l'esempio da seguire per quel caso.

## Convenzione: attributi che portano un URL dell'app

Ogni attributo che porta un URL dell'app — `href`, `src`, `action`,
`hx-get`/`hx-post`/`hx-put`/`hx-delete`, o un URL passato a un Web
Component (es. `connect` di `<deep-chat>`, vedi `deep-chat.html`) —
passa **sempre** da `@{...}`, anche quando il path e' letterale e non
dipende dal model. L'app puo' girare dietro un reverse proxy su un
subpath (vedi `server.forward-headers-strategy` in `application.yml`):
solo `@{...}` tiene conto del prefisso (`X-Forwarded-Prefix`) a
runtime — una stringa scritta a mano come `"/generations"` lo ignora
sempre, "statica" o no. Non e' teorico: e' un bug reale che si e'
presentato appena l'app e' stata montata sotto un subpath (vedi il fix
su `fragments/generate-form.html` e `deep-chat.html`).

Per un attributo `hx-*` standard basta il prefisso `th:` — Thymeleaf lo
riconosce come "generic attribute setter" anche per attributi non
standard come `hx-get` — con `@{...}` dentro, path letterale o con
parametri dal model:

```html
<form th:hx-post="@{/generations}" hx-target="#generation-panel" hx-swap="innerHTML"
      th:action="@{/generations}" method="post">
```

```html
<a th:hx-get="@{/gallery(page=${p})}" hx-target="#gallery-content" hx-swap="innerHTML">...</a>
```

Fuori da un attributo htmx (es. `connect` di `<deep-chat>`, letto dal
componente via JavaScript, non da htmx: non esiste un `th:connect`) non
c'e' un prefisso `th:` diretto da applicare: serve `th:attr` con
`@{...}` interpolato dentro una literal substitution Thymeleaf
(`|...|`), vedi `deep-chat.html`.

**Lato Java** (fuori da un template, quindi senza `@{...}`) lo stesso
prefisso va rispettato con `request.getContextPath()`: essendo
`server.forward-headers-strategy: framework` attivo, `ForwardedHeaderFilter`
riscrive il context path della richiesta a runtime per includere
`X-Forwarded-Prefix`, quindi `request.getContextPath() + "/gallery"`
riproduce esattamente cio' che `@{/gallery}` emetterebbe in un template
— non una stringa letterale `"/gallery"` scritta a mano, per lo stesso
motivo di sopra. Esempio: `GenerationController#delete`/`#deleteImage`,
che costruiscono l'header di risposta `HX-Redirect` cosi' (un
`redirect:"..."` come nome di vista, invece, non ha bisogno di questo:
Spring lo risolve gia' correttamente rispetto al context path da solo).

## Convenzione: theming

Nessun file CSS: tutto lo stile e' Tailwind (Play CDN, JIT nel browser),
configurato interamente nello `<script>`/`<style type="text/tailwindcss">`
inline in `fragments/layout.html`. Nessuna regola deve usare un colore
hardcoded fuori da quella config: sempre i nomi in `theme.extend.colors`.

**Design token** — `tailwind.config.theme.extend.colors`, un blocco
`{ DEFAULT, dark }` per ogni colore (es.
`canvas: { DEFAULT: '#ffffff', dark: '#0d1117' }`), usato nel markup come
coppia `bg-canvas dark:bg-canvas-dark`. Il suffisso `-dark` nel nome
della *shade* e il prefisso `dark:` della *variante* sono due cose
diverse che solo si assomigliano: il primo e' solo un colore in piu'
nella palette, il secondo (attivato da `darkMode` sotto) decide quando
usarlo. Nomi attuali: `canvas`/`surface`/`ink`/`ink-muted`/`line`/
`accent`/`accent-contrast`/`danger`.

**Dark mode** — `darkMode: ['selector', '[data-theme="dark"]']`: le
varianti `dark:` si attivano quando `<html>` porta `data-theme="dark"`,
non da `prefers-color-scheme` direttamente. Il toggle light/dark/auto
resta quello di sempre (script di boot + Alpine su `<body>` in
`layout.html`, invariati) — set/legge lo stesso attributo `data-theme`
via localStorage/`prefers-color-scheme`, semplicemente ora e' Tailwind a
consumarlo invece di un `[data-theme="dark"] { --color-x: ... }` scritto
a mano. Il boot dello script inline in `<head>` (prima degli script
Tailwind) e' li' per evitare il FOUC al primo caricamento: legge
`localStorage`, risolve `auto` in `light`/`dark`, e setta `data-theme`
sull'`<html>` PRIMA che Tailwind compili le classi — non spostarlo piu'
in basso nella pagina.

**Stili trasversali** (`@layer base` in `fragments/layout.html`) — solo
per elementi "nudi" usati identici e senza classi proprie in piu' punti
(link, `<code>`, form controls senza wrapper dedicato, `[x-cloak]`
richiesto da Alpine): meccanismo nativo Tailwind via
`<style type="text/tailwindcss">`, non un secondo sistema di stile.
Sicuro per costruzione: un selettore bare-tag li' ha specificita' bassa
(0,0,1)/(0,0,2), qualunque classe inline nei template vince sempre a
prescindere dall'ordine. Tutto il resto (bottoni-variante, card,
pannelli, paginazione...) e' utility Tailwind inline nel template, mai
una nuova regola `@layer`.

**Bottoni** — non scrivere un `<button>` a mano: usare i fragment
parametrici in `fragments/button.html` (`primary`/`danger`/
`themeToggle`), che incapsulano le classi Tailwind di ogni
variante in un solo posto. Chiamarli sempre con parametri nominati
(`frag(nome=valore)`, mai posizionali: un fragment con parametri
opzionali (es. `hxPost` su `danger`, `null` se l'htmx sta sul `<form>`
contenitore invece che sul bottone) richiede comunque **tutti** i
parametri dichiarati alla chiamata (anche quelli non usati, passati
`null` esplicitamente) — a differenza del pattern controller-fragment di
"Pattern controller: quando restituire fragment vs pagina intera" sopra,
qui *non* bastano i soli parametri che servono.

**`@click`/`:class`/altri binding Alpine con un valore dinamico per
istanza** (es. `themeToggle`, che deve scrivere `theme = 'light'` /
`'dark'` / `'auto'` a seconda del chiamante) non passano da `th:attr`:
il suo parser di assegnazione non accetta nomi con `@`/`:`. Si passa
invece il valore via un attributo `data-*` renderizzato da Thymeleaf
(`th:data-theme-value="${value}"`), letto a runtime con
`$el.dataset.themeValue` dentro l'espressione Alpine, che resta cosi'
puro HTML statico mai toccato da Thymeleaf — stesso principio del ponte
per `:title` sotto.

**Un binding Alpine puro** (`:title`, `:placeholder`...) non passa mai
da Thymeleaf server-side: `#{...}` su quell'attributo non avrebbe
effetto. Si ponte con `data-*` attributi server-renderizzati, letti a
runtime via `$el.dataset` — stesso meccanismo di `themeToggle` sopra,
applicato a un binding di sola lettura invece che a un'assegnazione.

**Limite del modello binario light/`dark:`**: a differenza del vecchio
`[data-theme="xxx"]` (un blocco CSS per qualunque nome di tema), le
varianti Tailwind gestiscono nativamente solo due stati per colore
(default + `dark:`). Un ipotetico terzo tema (es. "high-contrast") non è
un'estensione banale — richiederebbe ripensare la struttura dei colori
in `theme.extend.colors` (es. un colore per stato invece di due), non
solo aggiungere un blocco come prima. Non è un problema oggi (solo
light/dark/auto esistono), ma va tenuto presente prima di promettere
"aggiungere un tema" come un'operazione a costo fisso.

## Convenzione: migrazioni database (Flyway)

Lo schema del database **non** e' gestito da Hibernate: `spring.jpa.hibernate.ddl-auto`
e' `validate`, non `update`/`create`. Hibernate all'avvio controlla solo che
le tabelle create da Flyway corrispondano alle entity JPA — se non
corrispondono l'app non parte (fail-fast, niente drift silenzioso tra
codice e schema).

Ogni modifica alla persistenza (nuova entity, nuovo campo, nuovo indice,
rename di colonna...) va fatta con una **nuova migrazione SQL** in
`src/main/resources/db/migration/`, mai lasciando che sia
`ddl-auto: update` a dedurla da solo:

1. Aggiungere/modificare l'entity JPA come al solito.
2. Creare `V<N+1>__<descrizione>.sql` (numero progressivo, mai riusare
   uno gia' applicato — Flyway calcola un checksum di ogni file e fallisce
   l'avvio se un file gia' eseguito viene modificato) con il DDL H2
   corrispondente (`CREATE TABLE`, `ALTER TABLE ADD COLUMN`, ecc.).
3. Avviare l'app: Flyway applica automaticamente le migrazioni non ancora
   eseguite (traccia lo stato in `flyway_schema_history`) prima che
   Hibernate validi lo schema.

In sviluppo, se serve ripartire da zero (schema o dati inconsistenti),
si puo' cancellare l'intera `./data/db/` (e' stato locale, non versionato,
vedi sopra): Flyway ricrea tutto dalle migrazioni al prossimo avvio.

## Convenzione: internazionalizzazione (i18n)

Tutto il testo utente-visibile (template e messaggi d'errore Java) passa
dal meccanismo nativo di Spring/Thymeleaf (`MessageSource` + `#{...}`),
non da stringhe hardcoded: la lingua cambia **automaticamente** in base
all'header `Accept-Language` del browser, nessuno switcher manuale,
nessuna persistenza in cookie/sessione. Il `LocaleResolver` e'
`AcceptHeaderLocaleResolver`, gia' il default di Spring Boot MVC — nessun
bean `LocaleResolver`/`WebMvcConfigurer` da scrivere, basta
`spring.web.locale`/`spring.messages.*` in `application.yml`.

Due bundle in `src/main/resources/`: `messages.properties` (italiano,
default/fallback — usato anche per qualunque locale browser non mappata,
es. "de") e `messages_en.properties` (inglese). Per aggiungere una
lingua: nuovo `messages_<locale>.properties` con le stesse chiavi (un
test, `TemplateRenderingTests.messageBundlesHaveMatchingKeys`, verifica
che i bundle abbiano lo stesso keyset — aggiornarlo per confrontare
anche il nuovo file). Convenzione chiavi: punto-separate,
`<pagina-o-componente>.<categoria>.<elemento>` (es. `header.nav.home`,
`galleryDetail.title`, `replicate.error.tokenMissing`) — il namespace e'
solo convenzionale, il bundle resta un unico file piatto sia per le
stringhe di template sia per i messaggi d'errore Java.

**Regola apostrofi (non ovvia, causa bug silenziosi)**: Spring passa un
messaggio per `java.text.MessageFormat` **solo** quando la chiamata ha
argomenti non nulli. `#{key}`/`Messages.get(code)` senza parametri →
niente `MessageFormat` → apostrofi letterali restano non-escaped
(`l'app`, `puo'`); `#{key(${arg})}`/`Messages.get(code, args...)` con
parametri → **sempre** `MessageFormat` → un apostrofo letterale va
raddoppiato (`''`) o scompare silenziosamente. Stessa attenzione per un
argomento numerico (id, durata): passa per `NumberFormat` e inserisce
separatori di migliaia locale-dependent — usare il sotto-pattern
`{0,number,#}`, non un bare `{0}`.

Lato Java, `org.dual.replicate.i18n.Messages` (wrapper su
`MessageSourceAccessor`, che risolve implicitamente sulla locale della
richiesta corrente via `LocaleContextHolder`) va iniettato ovunque un
messaggio d'errore possa arrivare all'utente — non solo nei controller:
oggi in `ReplicateClient`, `SearxngClient`, `GenerationService`,
`ImageStorageService`, `GenerationController`, `DeepChatApiController`,
`DeepChatService`. La risoluzione avviene sempre al call site, prima di
costruire l'eccezione (`throw new ReplicateException(messages.get(...))`),
mai nel costruttore dell'eccezione. Attenzione ai nomi: se la classe usa
gia' `messages` come variabile locale per qualcos'altro (es.
`DeepChatService`, dove `messages` e' la lista di turni della
conversazione), chiamare il campo iniettato diversamente (li' e'
`i18n`) per evitare lo shadowing.

Un binding Alpine (`:title`, `:placeholder`, ecc.) non passa mai da
Thymeleaf server-side: `#{...}` su quell'attributo non avrebbe alcun
effetto. Si ponte con `data-*` attributi renderizzati dal server, letti
a runtime via `$el.dataset` (vedi il toggle del pannello impostazioni in
`deep-chat.html`) — pattern riusabile per qualunque altra stringa
client-only, non il pattern `th:inline="javascript"` gia' in uso altrove
in quel file per i payload JSON.

`<html lang="...">` e' risolto dal bundle stesso
(`html.lang=it`/`en` nelle properties, `th:lang="#{html.lang}"` sul
decoratore `fragments/layout.html`), **non** da `#{#locale.language}`:
su una locale non mappata il contenuto servito ricade comunque
sull'italiano, quindi `lang` deve seguire il bundle effettivamente
usato, non la locale richiesta, altrimenti mentirebbe sulla lingua reale
del testo. Le pagine (`index.html`, `generate.html`, ecc.) non devono
avere un `lang` letterale sul proprio `<html>`: per il comportamento di
merge di thymeleaf-layout-dialect (vedi sezione layout manager sopra),
un attributo letterale presente su entrambi i lati vince da quello della
pagina, vanificando il `th:lang` dinamico del decoratore.

Limite noto e accettato: `Generation.errorMessage` (persistito su DB
quando una generazione fallisce) viene risolto nella locale della
richiesta che ha generato l'errore e salvato gia' tradotto — se la
lingua del browser cambia dopo, il testo persistito resta congelato
nella lingua di scrittura (nessuna migrazione per salvare chiave+
parametri invece del testo risolto). Allo stesso modo il catch-all di
`DeepChatApiController` non puo' tradurre messaggi di eccezioni
arbitrarie di framework terzi (Spring AI, errori di rete): solo il
prefisso `"Errore nel contattare l'assistente: "` e' tradotto, il resto
resta quello che la libreria ha restituito.

## Comandi utili

Nessun Maven Wrapper incluso: serve Maven installato sulla macchina
(`mvn -v` per verificare). Se preferisci il wrapper, generalo con
`mvn wrapper:wrapper` e committa i file risultanti.

```bash
mvn spring-boot:run        # avvio in sviluppo (Thymeleaf cache=false, reload live)
mvn test                   # test
mvn clean package          # build del jar eseguibile
```

`mvn test` non tocca mai `./data/db/` (il DB H2 su file usato da
`mvn spring-boot:run`): il profilo Spring "test" e' attivato per ogni
esecuzione di Surefire (`<systemPropertyVariables>` in `pom.xml`, non
un'annotazione da ricordarsi su ogni classe `@SpringBootTest`), che
sovrascrive solo il datasource su H2 in-memory
(`src/test/resources/application-test.yml`). Prima di questo, i
`@SpringBootTest` (es. `TemplateRenderingTests`) scrivevano righe
`Generation` di prova vere (prompt "a cat"/"a dog") nello stesso DB su
file dell'ambiente di sviluppo a ogni run, quasi tutte senza
`@Transactional` — un problema reale osservato dal vivo, non solo
teorico.

## Checklist per aggiungere una nuova pagina/feature

1. Serve solo navigazione? → nuovo controller + nuovo template pagina
   con il pattern layout manager sopra. Basta.
2. Serve un aggiornamento parziale (ricerca live, paginazione, form senza
   reload)? → estrarre la porzione riusabile in `fragments/<nome>.html`,
   far restituire al controller quel fragment quando `HX-Request` è
   presente, la pagina intera altrimenti (vedi `GalleryController`).
3. Serve solo interattività locale, nessuna chiamata al server (aprire/
   chiudere un pannello, validare un campo)? → `x-data`/`x-show`/`x-on`
   di Alpine.js, direttamente nel template, senza controller dedicato.
4. Serve davvero un componente complesso stateful (editor, canvas,
   grafico)? → valutare un Web Component isolato prima di introdurre un
   framework SPA per l'intera app.
5. La feature tocca un'entity JPA (nuovo campo, nuova tabella, nuova
   relazione)? → nuova migrazione Flyway in `db/migration/` (vedi
   sezione dedicata sopra), mai affidarsi a `ddl-auto` per crearla.
6. Introduce testo letterale utente-visibile (label, messaggio
   d'errore)? → chiave in entrambi i bundle (`messages.properties`,
   `messages_en.properties`, vedi sezione i18n sopra), mai una stringa
   hardcoded in un template o nel costruttore di un'eccezione.
7. Serve un `<button>`? → uno dei fragment in `fragments/button.html`
   (vedi "Convenzione: theming"), mai scritto a mano nel template. Se
   nessuna variante esistente calza, aggiungere un nuovo fragment li',
   non un bottone inline isolato.
