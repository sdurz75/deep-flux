#set( $symbol_pound = '#' )
#set( $symbol_dollar = '$' )
#set( $symbol_escape = '\' )
#set( $p = '#' )
#set( $h2 = '##' )
#set( $h3 = '###' )
# CLAUDE.md

Guida di ${appName}, costruita su `hexa-core`#if( $useAi == "true" ) e `hexa-ai`#end: le convenzioni vengono dalle librerie hexa (le stesse del progetto da cui nasce questo template), qui solo cio' che serve per estendere l'app senza violarle. Le scelte sono vincoli deliberati per tenere il progetto snello: una violazione si corregge nel codice, non allentando una regola o un test. Java 21, Maven, nessun Maven Wrapper. Il package radice e' quello scelto alla generazione (`${package}`).

${h2} Scopo

Una sola app, un solo dominio: non aggiungere feature (demo, integrazioni, pattern) che non servano a quello scopo; un pattern htmx/Alpine nuovo si dimostra in una feature vera. La slice `example/` e' solo un modello da copiare e poi cancellare (con la sua migrazione, la pagina, la voce di menu, le chiavi di bundle, la pagina del manuale e i test). Oltre alla struttura esagonale mostra, funzionanti e testati, i punti in cui un'app si innesta in hexa-core:

- **Binario nello storage**: l'allegato passa da `IImageStorageService` (upload multipart -> `UploadedFile`, rifiuto atteso = `StorageException` `REJECTED` mostrato inline, file ripulito se la riga non si salva, servito da `/images/**`), e la colonna e' dichiarata da `ExampleBlobReferences` (`IBlobReferences`) con `BackupBlobColumnsTest` a fare da rete.
- **Eventi di sistema**: `ExampleEventSource` (+ `events.source.EXAMPLE`), `ISystemEvents${p}record` nel punto in cui si ingoia un guasto, `ExampleEventLinks` (`IEventLinkResolver`: subject `example:<id>` -> link nel registro), `HtmxEvents${p}addHxTrigger` per un evento verso il client.
- **Servizio esterno**: `ExampleRemoteClient` (`RestRemoteClient` + `RemoteCaller`: retry sui soli guasti `TRANSIENT`, `RetryPolicy.NONE` per le operazioni non idempotenti), `ExampleRemoteException` (`Kind`), pulsante «Controlla stato» in `/example`. L'errore risale senza `catch` al resolver del core, che lo registra una volta (registro, campanella, log) e manda il toast; `EXAMPLE_REMOTE_BASE_URL` assente = avviso `CONFIGURATION`. Test: `ExampleRemoteClientTest`.
- **Segreti**: `ExampleSecretTypes` (`ISecretTypeCatalog` + `secrets.type.EXAMPLE`): la pagina `/secrets` offre il tipo; il plaintext si ottiene solo con `ISecrets.resolve` dentro il servizio che lo usa.
- **Manuale**: `manual/it/01-uso/01-esempio.md`, `manual.group.uso`, voce `/manual` in `nav.html`.
- **Dipendenze**: `hexa-bom` in `dependencyManagement` (le versioni di hexa si cambiano da `hexa.version`).

${h2} Cosa e' di chi

- **hexa-core** (jar): layout e UI kit Thymeleaf, eventi di sistema (`ISystemEvents`, campanella, toast), segreti con tipo (`/secrets`: token API, password...), storage dei binari, backup (`export`/`import`), push SSE, manuale online (`/manual`). Config: `classpath:core.yml` (importato da `application.yml`).
#if( $useAi == "true" )
- **hexa-ai** (jar, sopra core): chat (`/deep-chat`), ricerca semantica (`/search`), chiamate LLM e visione, crediti OpenRouter. Config: `classpath:ai.yml` e `prompts.properties` (i testi dei prompt sono VOSTRI). Package `org.dual.hexa.ai.*` (sottosistemi `llm`, `chat`, `search`, `credits`), jar a parte.
#end
#if( $usePwa == "true" )
- **hexa-pwa** (jar, sopra core): app installabile, `/manifest.webmanifest` (nome da `app.title`/`app.brand`, quindi per lingua), `/sw.js`, shell `/offline`, icone segnaposto in `static/pwa/icons/`. Nessuna configurazione da importare. Per usare le vostre icone mettete file con gli stessi nomi (`icon-192.png`, `icon-512.png`, `icon-maskable-512.png`, `apple-touch-icon.png`, `icon.svg`) in `src/main/resources/static/pwa/icons/`; colori della barra del browser con `app.pwa.theme-color` e `app.pwa.theme-color-dark` (esadecimali: il manifest non accetta token Tailwind), `app.pwa.enabled=false` la spegne. Il service worker mette in cache SOLO `/offline`, gli asset statici (`js/`, `css/`, `pwa/`) e gli script dei CDN del layout: **mai** HTML dinamico (stessa URL, due risposte distinte da `HX-Request`), non-GET, SSE (`/events`) o binari (`/images/**`); non estenderlo per mettere in cache pagine o dati. Serve HTTPS (`localhost` esente).
#end
#if( $useOauth2 == "true" )
- **hexa-oauth2** (jar, sopra core): accesso con OAuth2/OIDC (Spring Security `oauth2-client`) e lista degli utenti ammessi (email esatte e domini). Nessun file da importare: l'essenziale arriva dalle variabili `HX_OAUTH2_*` (`.env.example`), il resto si gestisce dalla sezione «Accesso OAuth2» di `/settings` (menu «Gestione»): interruttore, provider (il segreto del client e' un segreto gestito di tipo `OAUTH2_CLIENT`, visibile in `/secrets`) e elenchi degli ammessi, additivi con le variabili. **Cancello spento = invisibile** (nessuna richiesta cambia); acceso, senza una sessione autenticata da un utente ammesso entra solo chi chiede i path esenti (asset, pagina di accesso, e quelli di `ILockExemptPaths`). Fallisce CHIUSO: acceso senza provider o senza ammessi, nessuno entra; recupero con `HX_OAUTH2_RESET=true` all'avvio. La lista si applica DENTRO l'autenticazione (`AllowlistOidcUserService`), con email verificata e dominio per uguaglianza; a cancello acceso il CSRF e' un controllo di stessa origine (`Sec-Fetch-Site`/`Origin`), non il token di Spring Security. Prima l'accesso OAuth2, poi, se attivo, il PIN. Spring Security c'e' SOLO con questa libreria e la sua catena di filtri e' di `hexa-oauth2`: non aggiungerne un'altra. Vedi il manuale `02-sviluppo/17-oauth2.md`.
#end
- **Questa app**: tutto sotto `${package}`. Le librerie NON conoscono l'app: si dipende da loro solo attraverso `port.in`, `domain` e il kernel (vedi Architettura).
- Punti di estensione obbligatori del layout: `templates/fragments/app/nav.html :: links(inline)` (voci della sidebar) e `templates/fragments/app/status-extras.html :: container` (a destra della barra di stato). `app.layout.nav: sidebar` in `application.yml` (`top` = barra in alto). Bundle: `app.brand` e `app.title` sono richieste dal layout del core.
- Una chiave di un file IMPORTATO (`core.yml`#if( $useAi == "true" ), `ai.yml`#end) vince su `application.yml`: si cambia da env/`.env`, da un profilo o da `-D`, non sovrascrivendola qui. Il `.env` sta nella radice (`spring-boot:run` parte da li').

${h2} Architettura (esagoni)

Un sottosistema per feature: `<package>/<feature>/{domain,application,port/in,port/out,adapter/in/web,adapter/out/persistence}`. Fuori dalle feature solo `Application` e `shared` (trasversale: non dipende dalle feature).

- **Naming**: interfacce SEMPRE con prefisso `I` (porte comprese), implementazioni senza, col ruolo/backend (`IExamples` -> `ExampleService`, `IExampleStore` -> `JpaExampleStore`). Repository Spring Data package-private in `adapter.out.persistence`.
- **Regole** (`ArchitectureTest` usa `HexaArchitectureRules` di `hexa-test-support`, le stesse del framework): `domain` = JDK + `jakarta.persistence` + Hibernate types + kernel; `application` usa solo `port`, niente adapter, `org.springframework.web|http|data|jdbc`, servlet, `java.sql`, I/O su file o immagini (stanno dietro una porta); `port` dipende solo da domain/port/kernel; `adapter.in` e `adapter.out` non si conoscono; un controller parla solo con porte `in`. Fra feature e verso le librerie hexa si dipende SOLO da `port.in` e `domain` (kernel e `core.web` esclusi); nessun ciclo. Con una seconda feature la regola e' gia' attiva: non serve aggiungerla.
- **Punti di estensione delle librerie** (l'host li implementa, il SERVIZIO li inietta come `Optional<...>`): `IBlobReferences` (backup), `IEventLinkResolver`, `ISecretTypeCatalog`#if( $useAi == "true" ), `ISearchableSource`, `IChatToolkit`, `IChatTurnContributor`, `IChatPageContributor`, `IChatOutcomeResolver`, `ICreditSource`#end. L'elenco e' chiuso: non implementare altre porte `out` delle librerie.
- **Nei commenti** citare classi di altri strati con `{@code Nome}`, mai `{@link}`.
- **Nuova feature**: disegnare PRIMA le porte (tipi di dominio, mai web/HTTP/Spring Data#if( $useAi == "true" )/Spring AI#end), poi gli adapter; se serve un dato di un'altra feature senza che ti conosca, una SPI nella tua `port.in`. Le colonne di collegamento fra feature sono `Long`, mai `@ManyToOne` verso un'altra feature; la FK c'e' solo nella direzione delle dipendenze.

${h2} Filosofia e cosa NON introdurre

Hypermedia-first, non SPA: server -> HTML. Spring MVC + Thymeleaf; aggiornamenti parziali htmx; micro-interattivita' Alpine; un componente complesso e stateful = Web Component isolato. Zero build frontend (htmx, Alpine, Tailwind da CDN nel layout del core).

- **No** WebFlux come modello del server (resta `spring-boot-starter-webmvc`; solo tipi Reactor dove lo impone una libreria), **no** React/Vue/Angular, **no** build Tailwind obbligatorio (opt-in: `mvn -Ptailwind clean package`), **no** CSS in `static/`. Classi Tailwind sempre stringhe LETTERALI (la CLI le scansiona).
- Le immagini/binari stanno in `./data/images`, il DB di sviluppo in `./data/postgres` (fuori da git).

${h2} Pattern Thymeleaf e controller

- **Pagine**: `layout:decorate="~{fragments/core/layout}"` sul proprio `<html>` (senza `lang` letterale), contenuto in `<div layout:fragment="content">` (NON un `<main>`). `<title>` sostituisce quello del layout. Pagine in `templates/app/`.
- **Due subtree di fragment, `app -> core`**: `fragments/core` (delle librerie) e `fragments/app` (i vostri, di dominio). I vostri fragment possono comporre quelli del core; mai il contrario. Se scrivete un fragment generico riusabile, i testi e gli URL arrivano gia' risolti come parametri.
- **Stessa URL, due risposte** distinte da `HX-Request` (fragment/pagina intera). Un fragment con parametri restituito come vista diretta richiede parametri **nominati** (`frag(items=${symbol_dollar}{items})`); la forma posizionale vale solo in `th:replace`. In un parametro di fragment NON si usano `@bean`, `new`, `T(...)` ne' mappe SpEL: calcolarli con `th:with`.
- **Breadcrumbs** su OGNI pagina tranne la Home: `fragments/core/breadcrumbs :: trail(group, parentPath, parentText, current)` nello slot `breadcrumbs`, parametri nominati e tutti passati (inutilizzati `null`), `parentPath` grezzo. Le pagine di sistema del core (Segreti, Eventi) usano il gruppo "Gestione" (`header.menu.manage`): tenere `navSystemCore` nel menu "Gestione" di `nav.html`.
- **Bottoni**: mai `<button>` a mano; fragment di `fragments/core/button.html` / `field-buttons.html` (o uno vostro in `fragments/app/`), parametri nominati e TUTTI passati. **Campi svuotabili**: `<input>` testo/search/date/number avvolti da `fragments/core/clear-field :: wrap(content=~{::#id}, clearable=..., title=...)` (id univoco, `clearable=false` = nudo). **Dialog/schede/interruttori**: `fragments/core/modal :: dialog`, `tabs :: list/tab`, `switch :: toggle` (mai scheletri, barre di tab o checkbox booleane scritti a mano); pannello ancorato con bottone proprio = `popover :: floating`; pannello laterale = `slideover :: drawer`. **Conferme**: `hx-confirm` (o `window.hexaConfirm(domanda)` da JS), mai `confirm()` nativo. **Select**: mai `<select>` nuda; wrapper `pinesSelect` (`fragments/core/select.html`); un cambio da codice si annuncia con `select.dispatchEvent(new Event('pines-select:sync'))`.
- **URL**: ogni attributo con un URL (`href`, `src`, `action`, `hx-*`) passa SEMPRE da `@{...}` (reverse proxy su subpath): `th:hx-post="@{/x}"`; fuori da htmx `th:attr` con `@{...}` in `|...|`. Lato Java `request.getContextPath() + "/x"`; `redirect:` no.
- **Alpine**: i binding non passano da `th:attr`/`${symbol_pound}{...}`: il valore dinamico in un `data-*` e `${symbol_dollar}el.dataset`.
- **Theming**: solo Tailwind, utility inline; mai colori hardcoded fuori da `theme.extend.colors` in `src/main/tailwind/tailwind.config.js` (unica config: Play CDN e CLI); token `{ DEFAULT, dark }` (`canvas|surface|ink|ink-muted|line|accent|accent-contrast|danger|warning`) come `bg-canvas dark:bg-canvas-dark`. Mai nuove regole `@layer`; il blocco `@layer base` e' in `input.css` E nel layout del core: non toccarlo.
- **Toast**: header `HX-Trigger` via `HtmxEvents${p}addToastHeader`/`addHxTrigger`. Nessun bottone "Riprova" sul toast (rieseguire una POST non idempotente duplicherebbe l'effetto, e se costa, la spesa). L'overlay "operazione in corso" blocca la UI durante i non-GET htmx (`data-busy`, `data-busy-text`, `data-busy-delay`).

${h2} i18n

Ogni testo utente-visibile da `MessageSource` + `${symbol_pound}{...}`; lingua da `Accept-Language`, default italiano. Tre bundle con chiavi **DISGIUNTE**: `messages-core*` (hexa-core)#if( $useAi == "true" ), `messages-ai*` (hexa-ai)#end e `messages*` (vostro, italiano + `_en`). Una chiave nuova va nel bundle del lato del codice che la usa, in ENTRAMBE le lingue; non ridefinire mai una chiave delle librerie (per cambiarne il testo, una chiave vostra). Chiavi `<pagina-o-componente>.<categoria>.<elemento>`. **Apostrofi** raddoppiati (`''`) nei messaggi con argomenti; argomenti numerici `{0,number,#}`. Lato Java: errori all'utente con `core.kernel.i18n.Messages`, risolti al call site prima dell'eccezione. I test verificano che it/en abbiano le stesse chiavi e che i bundle siano disgiunti.

${h2} Errori, eventi di sistema, servizi remoti

- Ogni chiamata a un servizio remoto e ogni errore interno passa da `ISystemEvents${p}record(operation, throwable[, subject])`: MAI un `catch` che ingoia o solo logga. `record` non lancia e consegna il toast alla prima occorrenza di una serie (5 min). Avvisi: `warn(source, operation, subject, message)` (messaggio gia' tradotto). Registra chi gestisce/ingoia l'eccezione (servizi in background, watcher); se risale a un controller, il controller.
- **`core.kernel.remote`**: `RemoteServiceException` (radice delle eccezioni dei servizi) porta `source()` e `kind()`: `TRANSIENT`, `PERMANENT`, `CONFIGURATION`, `REJECTED` (atteso, non si registra). Il resolver risponde 502 / 422 (toast, niente evento) / 500. `RemoteCaller${p}call` ritenta solo i `TRANSIENT`; **`RetryPolicy.NONE` ESPLICITA per le operazioni NON idempotenti o a pagamento**. `RestClientTranslator` = unica regola stato HTTP -> `Kind`.
- **Nuovo servizio remoto**: una vostra `EventSource` (enum) con `events.source.<X>` nel bundle in entrambe le lingue; `FooException extends RemoteServiceException`; client `extends RestRemoteClient` che implementa la `port.out`, ogni chiamata in `remote.call(...)`. Un nuovo client HTTP usa il `RestClient.Builder` iniettato, mai `RestClient.create()`.
- **Nessuno stato indefinito**: un'operazione asincrona non lascia stati parziali (errore -> stato terminale + risorse ripulite); recovery all'avvio per cio' che e' rimasto a meta'.

${h2} Blocco con PIN e estensioni del layout (hexa-core)

Il blocco con PIN (`core.lock`) e' gia' in ogni app: opt-in da `/security` (menu «Gestione»), senza PIN non cambia nulla. **Cancello lato server** (`LockInterceptor` su `/**`): a sessione bloccata passano solo `/unlock`, `/lock/now`, `/js/`, `/css/`, i path di `ILockExemptPaths` (librerie) e `app.lock.exempt-paths` (vostri); tutto il resto, `/events` e `/images/**` compresi, e' redirect a `/unlock?next=` (navigazione) o 401. Stato nella sessione HTTP, aggiornato solo da input VERI dell'utente (`POST /lock/touch`): il polling non deve contare. PIN = hash PBKDF2 in `app_lock` con rallentamento persistito dei tentativi; ogni modifica chiede il PIN attuale; recupero con `app.lock.reset=true` all'avvio (da togliere dopo). Non cifra nulla; senza `hexa-oauth2` non c'e' Spring Security e il PIN e' l'unico accesso. Una libreria opzionale con UI si innesta nel layout del core implementando `ILayoutContributor` (`head`, `bodyEnd`, `manageMenu`), mai con template omonimi in due jar; i suoi fragment non hanno parametri (i dati arrivano dal model). Vedi il manuale `02-sviluppo/16-blocco-con-pin.md`.

${h2} Segreti

CRUD in `/secrets` (UN'entita' `Secret` con un `type` stringa: il core ha `API_TOKEN`, `PASSWORD`, `GENERIC`; l'app e i moduli ne registrano altri con un `ISecretTypeCatalog`, etichette `secrets.type.<NOME>`; un tipo `managed` e' dei moduli e non si modifica da li'). Dopo il salvataggio solo gli ultimi 4 caratteri; mai il segreto in log, eventi, toast o Model. Cifratura `ISecretCipher` con la STESSA chiave dei binari WebDAV (`app.secrets.encryption-key`, base64 di 32 byte, `openssl rand -base64 32`); persa = segreti e binari WebDAV irrecuperabili. Senza chiave `isConfigured()` e' falso e salvare e' `CONFIGURATION`.

${h2} Storage dei binari

Tutto cio' che l'app serve come file passa da `IImageStorageService` (nessun accesso diretto a filesystem/WebDAV, nessun resource handler statico: `/images/**` e' del core). Backend `storage.type=local|webdav`; lancia SOLO `StorageException`; espone `UploadedFile`, non `MultipartFile`. Ogni binario nuovo = `StorageNames${p}newFilename` (nome opaco, mai derivato da id/URL/nome originale, nessuna dedup). Con WebDAV i contenuti sono SEMPRE cifrati.

${h2} Persistenza e migrazioni (Flyway)

- Ogni modifica alla persistenza = **nuova migrazione SQL PostgreSQL** in `src/main/resources/db/migration/app/`, nome `V<AAAA>_<MM>_<GG>_<HHMM>__<descrizione>.sql`; mai modificare un file gia' eseguito (il checksum fa fallire l'avvio). Nessuna `spring.flyway.locations`: il default scansiona le sottocartelle (`core`, `ai`, `app`).
- **Ordine**: le migrazioni dell'app devono essere PIU' RECENTI delle baseline delle librerie (core `V2026_10_01_1200`#if( $useAi == "true" ), ai `1210`#end) e `outOfOrder` e' falso: dopo un aggiornamento di hexa che porti nuove migrazioni, controllare che le loro versioni non precedano quelle gia' applicate dall'app.
- **Dialetto**: identificatori minuscoli non quotati; testo lungo `text` + `@JdbcTypeCode(SqlTypes.LONGVARCHAR)` (MAI `@Lob`); `timestamptz`, `bytea`; enum Java = `varchar` senza ENUM/CHECK; `ddl-auto: validate`. In sviluppo: `docker compose down && rm -rf data/postgres`.
- **Nessuna FK dalle librerie all'app**; una libreria legge i dati dell'app solo tramite le sue SPI.

${h2} Backup e restore

`java -jar app.jar export <file> [--no-encrypt]` / `import <file> [--replace]` (esito 0/1/2): profilo `backup`, senza web ne' lavori in background. Esporta TUTTE le tabelle (tranne `flyway_schema_history`) e i binari REFERENZIATI dal DB: **una colonna con il nome di un binario (`...filename...`) va dichiarata con un `IBlobReferences`** (coppie tabella/colonna), altrimenti i binari restano fuori dal backup senza errori. Credenziali: solo `HX_DB_*` e, con WebDAV, `HX_STORAGE_WEBDAV_*`. `backup.encryption-key` ripiega su `storage.webdav.encryption-key`; senza chiave l'export RIFIUTA (serve `--no-encrypt`). Un backup vecchio entra in un jar nuovo, non viceversa. Una feature con binari propri aggiunge un test di round-trip sul proprio schema (esporta, importa in un DB vergine, confronta tabelle e binari).

${h2} Manuale online

Markdown in `src/main/resources/manual/<lingua>/<NN-gruppo>/<NN-pagina>.md`, servito da `/manual` (voce di menu a carico dell'app); slug = nome file senza prefisso, unico fra tutti i gruppi; etichetta del gruppo `manual.group.<gruppo>` nel bundle dell'APP; titolo = primo `${symbol_pound} `; un solo `${symbol_pound}` per pagina; link fra pagine col nome VERO del file (`../01-uso/03-x.md${p}ancora`), verso l'app un path radice (`/example`). Il gruppo `02-sviluppo` (con il catalogo di TUTTI i fragment di `fragments/core`, da tenere allineato) e' la guida dello sviluppatore su hexa-core/hexa-ai (con link al javadoc su GitHub Pages): e' distribuita dall'archetype come esempio di manuale e si puo' modificare o cancellare (con la chiave `manual.group.sviluppo`). Quando cambia cio' che l'utente vede o fa, si aggiorna il manuale: nessun test blocca il testo vecchio, solo i link morti.
#if( $useAi == "true" )

${h2} AI: chat, ricerca, prompt (hexa-ai)

- **Chat** (`/deep-chat`, nel core con slot dell'host): la cronologia e' lato SERVER, il client manda solo l'ultimo messaggio. SPI verso l'host (la chat non conosce il dominio): `IChatToolkit` (tool; `promptSection` e `@Order`), `IChatTurnContributor` (contesto del turno come `ToolContext`), `IChatPageContributor`, `IChatOutcomeResolver`. La pagina la riempie UN template dell'host (`app.chat.host-fragment`, slot `intro|settings|below|scripts|conversationTags|knownTags`); lo script di `scripts` definisce `window.deepChatHost` PRIMA del modulo di core.
- **Tool e prompt**: ogni tool implementa `promptSection`; le sezioni stanno in `prompts.properties` (`deep-chat.section.<nome>`; `core` e `guidance` sempre, le altre solo per i tool presenti) e il prompt complessivo ha un tetto (10.000 caratteri): scrivere sezioni brevi e testarlo. I RITORNI dei tool sono in INGLESE, dicono di non riprovare e di avvisare l'utente; i tool catturano da soli i guasti (`record`) e restituiscono i rifiuti attesi com'e'. Un tool che muta e' IDEMPOTENTE (mai toggle); un tool a pagamento ha un tetto per turno. Il risultato dei tool `propose*` NON esegue: deposita un'azione resa come bottone.
- **Link in chat**: elenco CHIUSO di path (`app.chat.link-paths`, `app.chat.entity-link-paths`); una nuova pagina citabile va li' e in una sezione di prompt che elenca le pagine (da inserire in `app.chat.prompt-sections.leading`).
- **Ricerca semantica** (`/search`, stesso Postgres/pgvector): UN documento per entita' indicizzata via `ISearchableSource` (un nuovo tipo = una nuova fonte nel suo sottosistema; metadata con chiavi riservate `contentHash`, `embeddingModel`, `indexedAt`; niente null nei metadata). Slot dell'host in `app.search.host-fragment`. Le note (`type=note`) sono modificabili, i derivati in sola lettura. Embedding locali (~120 MB in `./data/models` al primo avvio); nessun indice ANN di proposito.
- **Test**: `app.search.enabled=false` e `spring.ai.model.embedding: none` (gia' in `application-test.yml`) spengono indice ed `EmbeddingModel`. Mai chiamate vere a OpenRouter/SearXNG: `@MockitoBean` sulle porte o `MockRestServiceServer`.
- **Servizi di visione opzionali**: `IImageDescriber` (`imageAnalysis.guide`), `IImageCaptioner` (`trainingCaption.subject-guide` + `style-guide`) e `IPromptEnhancer` (le cinque `generateForm.*-guide`) esistono solo se definite le loro guide in `prompts.properties`; un bean che li inietta senza guida non parte. Le guide di visione NON includono il contesto creativo dell'app, e un rifiuto del modello e' un esito atteso (nessun toast), un guasto no (`record`).
- `searxng.base-url` e' facoltativo (vuoto = la ricerca web risponde che non e' configurata); `HX_OPENROUTER_API_TOKEN` per chat e visione; `HX_OPENROUTER_MANAGEMENT_KEY` solo per il chip dei crediti.
#end

${h2} Test

- `mvn test` richiede Docker: `hexa-test-support` avvia UN container PostgreSQL#if( $useAi == "true" )+pgvector#end usa-e-getta e Flyway applica lo schema reale; non tocca mai il DB di sviluppo. Surefire fissa il profilo `test`, `storage.type=local`, la migrazione spenta e una chiave di test.
- I test condividono il DB: i `@SpringBootTest` che scrivono sono `@Transactional` o ripuliscono a mano.
- **Mai chiamate vere a servizi remoti nei test**: `@MockitoBean` sulle porte `out` verso l'esterno, `MockRestServiceServer` per i client; con uno stub gia' lanciante ri-stubbare con `doReturn(...).when(mock)`.
- Test di regole gia' presenti: `ArchitectureTest` (layering), bundle it/en con le stesse chiavi e disgiunti dalle librerie, rendering delle pagine. Una pagina nuova ha il suo test di rendering (pagina intera e fragment htmx).

${h2} Nuova pagina o feature

1. Feature nuova: porte prima, poi adapter; copiare `example/`. 2. Controller in `adapter/in/web` (solo porte `in`) + template `templates/app/<pagina>.html` col layout del core. 3. Voce in `nav.html` e breadcrumbs. 4. Aggiornamento parziale -> fragment in `fragments/app/`, restituito se `HX-Request`. 5. Testi in entrambi i bundle. 6. Entity -> migrazione Flyway. 7. `<button>` -> fragment; `<select>` -> `pinesSelect`. 8. Evento che l'utente deve notare -> `ISystemEvents${p}warn`/`${p}record`. 9. Binari -> `IImageStorageService` e `IBlobReferences`. 10. Cambia cio' che l'utente vede o fa -> manuale. 11. `mvn test` verde (`ArchitectureTest` compreso).

${h2} Comandi

```
cp .env.example .env && docker compose up -d      # DB di sviluppo (HX_DB_NAME/HX_DB_USERNAME/HX_DB_PASSWORD nel .env)
mvn spring-boot:run                               # sviluppo
mvn test                                          # test (Docker)
mvn test -Dtest=ArchitectureTest                  # solo architettura (senza Docker)
mvn -Ptailwind clean package                      # CSS compilato (opzionale)
java -jar target/*.jar export backup.dfb          # backup
```
