# Usare il repo come template per una nuova webapp

Il codice e' diviso in due:

- **`core`** (`org.dual.replicate.core`): la parte riusabile, indipendente da cio' che fa l'app. Si tiene.
- **`app`** (`org.dual.replicate.app`): l'applicazione attuale (generazione immagini via Replicate, galleria, deep-chat, LoRA,
  ricerca semantica). Si **sostituisce** con la propria.

Riuso = copiare il repo (stesso modulo Maven, niente artefatto separato), cancellare `app` e cio' che le appartiene, scrivere la propria
app implementando i punti di estensione sotto. La regola `coreDoesNotKnowApp` di `ArchitectureTest` impedisce che il codice di `core`
dipenda da `app`; ogni sottosistema (di `core` e di `app`) e' un esagono (domain / application / port.in / port.out / adapter.in /
adapter.out), per le regole vedi "Architettura" in [`CLAUDE.md`](../CLAUDE.md).

## Cosa offre il core

| Sottosistema | Cosa da' | Cosa si aspetta dall'app |
|---|---|---|
| `core.kernel` | `RemoteCaller`/`RetryPolicy`/`RestClientTranslator`/`RestRemoteClient` (errori e retry delle chiamate remote), `RemoteServiceException`, `Messages` (i18n), `ChunkedAesGcmCipher`, `EventSource`, `Paged`, `ToastMessage` | - |
| `core.events` | registro eventi di sistema (`ISystemEvents`: `record`/`warn`), pagina `/system/events`, campanella, toast, `UnhandledExceptionResolver`, `AsyncErrorConfig` | opzionale `IEventLinkResolver`; le proprie `EventSource` |
| `core.push` | SSE `GET /events` (`EventStreamController`), `IClientPush` (emit), `IClientPushStream`, `PushModelAdvice`, `fragments/core/live-events.html` | i nomi degli eventi propri in `app.push.client-events` |
| `core.secrets` | `ISecretCipher` (AES-256-GCM, stessa chiave dei binari WebDAV) | - |
| `core.tokens` | CRUD token API cifrati in `/tokens`, scadenza con avvisi (`TokenExpiryScheduler`) | opzionale `ITokenProviderCatalog` (senza: nessun provider selezionabile) |
| `core.storage` | `IImageStorageService` (binari su filesystem locale o WebDAV cifrato, cache, migrazione), `ImageController` (`/images/**`) | - |
| `core.backup` | comandi `export` e `import` del jar (`java -jar app.jar export <file>`): backup completo (tabelle scoperte da `information_schema` + binari) in un archivio cifrato; profilo `backup` (`application-backup.yml`) | la SPI `IBlobReferences` (`port.in`): le coppie (tabella, colonna) con i nomi dei binari; senza, si esporta solo il DB |
| `core.web` | `HtmxEvents`, `PaginationSupport`, `TailwindAssets`, `BuildInfo` | - |
| template e bundle | `templates/fragments/core/*` (layout, header, bottoni, select Pines, toast, paginazione...), `templates/core/*` (`/system/events`, `/tokens`), `messages-core(.en).properties` | vedi "Punti di estensione" |
| config | `core.yml` (importato da `application.yml`): server/proxy, multipart, thymeleaf, datasource, JPA, i18n, `app.secrets`, `app.tokens`, `app.events`, `storage.*` | - |
| Flyway | `db/migration/core/V2026_10_01_1200__core_baseline.sql`: tabelle `system_event` e `api_token` | - |

`ApiTokenProvider`, `ReplicatePricing`, il catalogo modelli ecc. NON sono core: stanno in `app.generation`.

## Cosa cancellare o sostituire

Percorsi relativi alla radice del repo; `<pkg>` = `src/main/java/org/dual/replicate`.

**Java**

- `<pkg>/app/` per intero (`chat`, `generation`, `prompt`, `search`, `shared`, `OpenRouterCalls`, `AppStartupOrder`). Attenzione: `app/shared`
  contiene anche `HomeController` (route `/` -> vista `app/index`), `AppEventSource`, `AppEventLinks`: vanno riscritti nella nuova app.
- `<pkg>/Application.java` resta (non cita nulla dell'app). `@EnableAsync` e `@EnableScheduling` stanno nel core
  (`core.kernel.ExecutionConfig`): il core ne ha bisogno (`TokenExpiryScheduler` e `@Async` dei listener).
- Bundle: le pagine del core mostrano, se presenti nel bundle dell'app, le righe `events.intro.app` e `tokens.intro.app` (testo
  specifico dell'app: servizi e provider); in un'app nuova si riscrivono o si omettono.
- `tailwind.config.js` e' condiviso: il token colore `favourite` e' dell'app (stella dei preferiti), toglierlo se non serve.

**Template e fragment**

- `src/main/resources/templates/app/` (8 pagine: `index`, `generate`, `generation-status`, `generations-list`, `gallery`, `deep-chat`,
  `loras`, `search`).
- `src/main/resources/templates/fragments/app/` (23 file). Da **riscrivere**, non solo cancellare: `fragments/app/nav.html`
  (vedi sotto). Le pagine app decorano `fragments/core/layout` con `layout:decorate`: la nuova pagina iniziale va fatta allo stesso modo.
- `src/main/resources/templates/core/` e `fragments/core/` restano.
- `fragments/core/layout.html` carica da CDN i plugin Alpine `focus`, `collapse` e `intersect` (commenti: lightbox della galleria, accordion della
  chat, miniature video lazy di `/search`). Sono utili al core solo se la nuova app usa quei componenti; altrimenti si possono togliere (lasciare `x-collapse`/`x-trap`/`x-intersect` ai
  fragment che li usano).

**Bundle i18n**

- `messages.properties` / `messages_en.properties` sono il bundle dell'**app**: si riscrivono, ma devono continuare a definire
  `app.brand`, `app.title` (le usano `fragments/core/header.html` e `layout.html`, verificato con grep) e le chiavi di
  nav che la nuova `nav.html` referenzia. Va tenuta anche `events.source.<NAME>` per ogni `EventSource` propria
  (e `events.link.*` se si implementa `IEventLinkResolver`), e `tokens.provider.<NOME>` per le etichette dei provider di token (facoltative: senza,
  `/tokens` mostra il nome del provider). `messages-core*.properties` restano.
- I due bundle devono avere chiavi **disgiunte** e identiche tra `it` ed `en` (test `coreAndAppBundlesDefineDisjointKeys` e
  `messageBundlesHaveMatchingKeys` in `TemplateRenderingTests`).
- `prompts.properties`: testo dei system prompt dell'app (importato da `application.yml`), si cancella con l'app.

**Database**

- `src/main/resources/db/migration/app/` si sostituisce con le migrazioni della nuova app. Le versioni sono **timestamp**
  (`V2026_10_01_1201__...`) in entrambe le location, cosi' le due sequenze si fondono senza conflitti: usare un timestamp
  PIU' RECENTE di quelli del core (e di ogni futuro aggiornamento del core).
- `V2026_10_01_1201__app_baseline.sql` contiene `CREATE EXTENSION vector` e `vector_store`: servono solo alla ricerca semantica. Senza,
  `compose.yaml` puo' usare un `postgres` normale al posto di `pgvector/pgvector:pg17` (e `PostgresTestContainerInitializer` lo stesso
  per i test).
- `application.yml` -> `spring.flyway.locations` elenca `core` e `app`: se la nuova app ha altre location, si cambia li'.

**Configurazione** (`src/main/resources/application.yml`, tutto cio' e' dell'app)

- `spring.ai.*` (OpenAI/OpenRouter, embedding `transformers`, ONNX/tokenizer) e `enhancer.*`;
- `replicate.*`, `searxng.*`;
- `app.recovery.*`, `app.search.*`, `app.push.*` (ma `app.push.client-events` / `reconnect-events` restano necessarie se la nuova app
  emette eventi SSE propri: vedi sotto);
- `spring.config.import` cita `classpath:prompts.properties` (togliere se si cancella il file). `core.yml` va tenuto importato.
- `src/test/resources/application-test.yml` imposta `app.recovery.enabled`, `app.search.enabled` (chiavi dell'app; innocue se mancano
  i bean) e `spring.ai.model.embedding: none`. `app.tokens.expiry-check-enabled: false` e' del core.

**Variabili `.env`** (vedi `.env.example`): dell'app sono `REPLICATE_API_TOKEN`, `OPENROUTER_API_TOKEN`, `OPENROUTER_CHAT_MODEL`,
`OPENROUTER_VISION_MODEL`, `OPENROUTER_VISION_FALLBACK_MODEL`, `SEARXNG_*` (compreso `SEARXNG_BASE_URL`; i modelli e l'URL sono override
commentati nell'esempio). Del core: `DB_*`,
`STORAGE_WEBDAV_*`.

**`pom.xml`** (OBBLIGATORIO, verificato): togliere le dipendenze `spring-ai-starter-model-openai`, `spring-ai-vector-store`,
`spring-ai-pgvector-store`, `spring-ai-starter-model-transformers`. Lasciarle senza il `spring.ai.*` di `application.yml` fa FALLIRE l'avvio
del contesto (l'autoconfig OpenAI vuole una API key: `At least one credential source must be specified`). `reactor-core` si tiene
(lo usa `IClientPushStream` del core). Il `systemPropertyVariables` di Surefire (`storage.type`, chiave di test dei segreti) e' del core.

**Test** (`src/test/java/org/dual/replicate/`)

- Cancellare: `app/` per intero, `controller/TemplateRenderingTests` (quasi tutto sulle pagine dell'app; i test generici su `/system/events`,
  `/tokens`, bundle, select e toast hanno gia' una copia nei test di `core/`), `config/FlywayCoreAppMigrationTest` (controlla anche le tabelle
  `generation` e `vector_store` dell'app).
- `app/generation/adapter/out/backup/BackupRoundTripTest` e' lo scenario di riferimento del backup (schema vero, due database dedicati): con `app/` se ne va, e la nuova app ne scrive uno
  sul proprio schema (stessa struttura: esporta, importa in un DB vergine, confronta tabelle e binari, controlla le sequenze).
- Restano e passano senza `app/`: `ApplicationTests` (carica il contesto), `architecture/ArchitectureTest`, `core/**`, `support/PostgresTestContainerInitializer`
  (+ `META-INF/spring.factories`). I test del core non dipendono dall'app: `SystemEventServiceTest` usa una `EventSource` locale al test,
  `TokenControllerTest` porta un proprio `ITokenProviderCatalog` (`@Primary`, vince su quello dell'app).
- Quando si scrivono nuovi test di `core/`: niente import da `app`, e non assumere un nav/bundle dell'app (la voce di menu "Token" la compone il nav
  dell'app, la sua presenza si verifica in un test dell'app).

## Punti di estensione

1. **Navigazione**: `templates/fragments/app/nav.html`, fragment `links(inline)`. `fragments/core/header.html` lo include due volte
   (barra con `inline=false`, slideover sotto `md` con `inline=true`). Si compone con i fragment del core `header :: navMenu`,
   `navLink`, `navSystemCore` (voci Eventi e Token). Senza questo file il core non rende. Le pagine di sistema del core
   (Token, Eventi) mostrano nelle breadcrumbs il gruppo "Gestione" (`header.menu.manage` in `messages-core`), cioe' il menu che ospita
   `navSystemCore`: una nuova app mantiene quel raggruppamento o cambia le breadcrumbs di quelle due pagine.
   Ogni pagina (tranne la Home) usa `fragments/core/breadcrumbs :: trail(...)` nello slot `breadcrumbs` del layout.
1b. **Barra di stato in basso**: `templates/fragments/app/status-extras.html`, fragment `container` (a destra della barra del core, accanto al selettore
   del tema; in questa app i crediti Replicate/OpenRouter). Senza questo file il core non rende: una nuova app lo lascia vuoto (`<div th:fragment="container"></div>`).
2. **Chiavi di bundle `app.brand`, `app.title`** nel bundle dell'app (le uniche chiavi dell'app richieste da template del core).
3. **Pagina iniziale**: il core non ha una route `/` (il link del brand punta a `@{/}`): serve un controller dell'app (oggi `HomeController`)
   e una pagina che decori `fragments/core/layout`.
4. **`EventSource`** (`core.kernel`): un'interfaccia con `name()`. Il core ha `CoreEventSource` (`STORAGE`, `TOKENS`, `INTERNAL`); l'app
   definisce un proprio enum che la implementa (oggi `AppEventSource`: `REPLICATE`, `OPENROUTER`, `SEARXNG`, `LORAS`) e per ogni valore aggiunge
   `events.source.<NAME>` nel bundle. Un nuovo servizio remoto: eccezione che estende `RemoteServiceException`, client che estende
   `RestRemoteClient`, errori registrati con `ISystemEvents#record` (dettagli in `CLAUDE.md`).
5. **`IEventLinkResolver`** (`core.events.port.out`, opzionale): da un `subject` dell'evento (es. `generation:12`) a un link "apri" nella
   pagina eventi. Oggi `AppEventLinks`. Se non c'e' nessun bean, nessun link (`ObjectProvider`).
6. **`ITokenProviderCatalog`** (`core.tokens.port.out`, opzionale): i nomi dei provider di token (stringhe) tra cui scegliere in `/tokens`. Oggi
   `AppTokenProviders` (CivitAI, HuggingFace). Senza bean la pagina non offre provider. Chi usa un token lo risolve da `IApiTokens`.
7. **Eventi SSE dell'app**: il core emette solo `system-event`. Gli altri si pubblicano con `IClientPush#emit(nome, payload)` e vanno
   elencati in `app.push.client-events` (separati da virgola; `app.push.reconnect-events` per quelli da ri-dispatchare alla riconnessione):
   `PushModelAdvice` li passa a `fragments/core/live-events.html`, che li ri-dispatcha come `CustomEvent` su `document.body`.
8. **Storage dei binari**: l'app lo usa solo tramite `IImageStorageService`; nessun accesso diretto al filesystem/WebDAV.
9. **Segreti e token**: `app.secrets.encryption-key` riusa `STORAGE_WEBDAV_ENCRYPTION_KEY`; con `storage.type=local` e' facoltativa (senza,
   `/tokens` segnala che manca).

## Rinominare il package radice

`org.dual.replicate` compare nei sorgenti, in `META-INF/spring.factories` (test), in `ArchitectureTest` e nel `groupId` del `pom.xml` (`org.dual`,
che di per se' non deve combaciare). Procedura (esempio verso `com.acme.shop`); farla su un commit pulito e poi eseguire la suite:

```bash
OLD=org/dual/replicate; NEW=com/acme/shop
mkdir -p src/main/java/$NEW src/test/java/$NEW
git mv src/main/java/$OLD/* src/main/java/$NEW/
git mv src/test/java/$OLD/* src/test/java/$NEW/
find src -type d -empty -delete
# riferimenti testuali (java, yml, properties, factories, html)
grep -rl 'org\.dual\.replicate' src pom.xml CLAUDE.md docs README.md \
  | xargs sed -i '' 's/org\.dual\.replicate/com.acme.shop/g'   # Linux: sed -i senza ''
# groupId/artifactId (facoltativo)
sed -i '' 's#<groupId>org.dual</groupId>#<groupId>com.acme</groupId>#' pom.xml
```

Controlli dopo il rename: `ArchitectureTest.ROOT` (stringa letterale `"org.dual.replicate"`, aggiornata dal `sed` solo se ha esattamente quel
formato: verificarla), `src/test/resources/META-INF/spring.factories` (cita `...support.PostgresTestContainerInitializer`), i FQCN nelle
query JPQL e nelle annotazioni, `@AnalyzeClasses(packages = ...)`. Dopo: `rm -rf target && mvn -q -o test`.

## Verifica del confine: cosa e' stato provato e cosa no

**Provato con `mvn test` nel repo** (richiede Docker):

- `ArchitectureTest`: `coreDoesNotKnowApp`, regole di esagono per ogni sottosistema, assenza di cicli, e la regola di chiusura
  `nothingOutsideCoreAndApp`.
- Bundle `core` e `app` con chiavi disgiunte e uguali tra le lingue; Flyway applica le migrazioni core+app come una sola sequenza e una
  migrazione core piu' recente si applica sopra (`FlywayCoreAppMigrationTest`).

**Provato su una copia senza `app/`** (export pulito del commit, una volta, a mano: non e' un test automatico del repo). Passi eseguiti:
cancellati `app/` (main e test), `templates/app`, `templates/fragments/app`, `db/migration/app`, `prompts.properties`, `TemplateRenderingTests`,
`FlywayCoreAppMigrationTest`; `application.yml` ridotto a `spring.config.import` (`.env` + `core.yml`), `spring.application.name` e
`spring.flyway.locations: classpath:db/migration/core`; i bundle `messages*.properties` ridotti a `app.brand|title|footer`; aggiunto lo stub
`fragments/app/nav.html` con un fragment `links(inline)` vuoto; tolte dal `pom.xml` le quattro dipendenze `spring-ai-*`.
Esito: `mvn -o test` verde (142 test: contesto, `ArchitectureTest` con la regola di chiusura, eventi di sistema con `/system/events`, `/tokens`, push,
secrets, storage). Senza il passo sul `pom.xml` l'avvio falla (vedi sopra); senza correggere `SystemEventServiceTest` e `TokenControllerTest`
(che dipendevano da `AppEventSource` e dal catalogo dei provider dell'app) la suite non compilava / non passava: ora e' corretto nel repo.

**NON provato**: la pagina iniziale `/` e una vera app sopra il core (la copia non aveva `HomeController`); l'avvio dell'applicazione completa
(non solo i `@SpringBootTest`) senza `app/`; il profilo Maven `tailwind` (`mvn -Ptailwind clean package` richiede rete per scaricare il binario).

## Checklist finale per una nuova webapp

- [ ] Rename del package radice e del `groupId`/`artifactId`/`spring.application.name`.
- [ ] `app/` cancellata e riscritta; `HomeController` + pagina iniziale.
- [ ] `fragments/app/nav.html` e chiavi `app.brand|title` nei bundle `it` ed `en`.
- [ ] Una propria `EventSource` (+ `events.source.<NAME>`), eventuali `IEventLinkResolver` / `ITokenProviderCatalog`.
- [ ] `app.push.client-events` aggiornata (o vuota) per gli eventi SSE propri.
- [ ] Migrazioni app con timestamp piu' recente di quelli del core; `spring.flyway.locations` coerente.
- [ ] `application.yml`, `.env.example` e `pom.xml` ripuliti da Replicate/OpenRouter/SearXNG/Spring AI (le dipendenze `spring-ai-*` vanno tolte: senza `spring.ai.*` l'avvio fallisce).
- [ ] Test dell'app riscritti; `ArchitectureTest` verde (la regola di chiusura vieta package fuori da `core`/`app`).
- [ ] `docker compose down && rm -rf data/postgres` per ripartire da zero: i baseline Flyway sono cambiati rispetto a un database creato con il
  vecchio schema.
