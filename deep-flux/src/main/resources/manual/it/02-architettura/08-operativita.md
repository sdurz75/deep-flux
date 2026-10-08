# Operatività

Come si avvia, si configura, si pubblica e si salva il sistema.

## Primo avvio

Servono Java 21, Maven (non c'è il wrapper), Docker per il database e un token API di Replicate.

1. Copia `.env.example` in `.env` (escluso da git) e imposta almeno `HX_DB_PASSWORD` e `REPLICATE_API_TOKEN`. L'app legge `.env` dalla cartella da cui parte il processo.
2. Avvia il database: `docker compose up -d`.
3. Avvia l'app: `mvn spring-boot:run`.
4. Al primo avvio con la ricerca attiva, l'app scarica il modello di embedding (circa 120 megabyte) in `./data/models`.

Senza il token di Replicate la navigazione funziona comunque; la generazione fallisce con un messaggio chiaro. La porta su cui ascolta è la proprietà `server.port` in `core.yml`.

## La configurazione

Due file, con chiavi disgiunte: `application.yml` per l'app (Spring AI, Replicate, SearXNG, `app.*`) e `core.yml` per il core (web, database, eventi, segreti, storage), più `prompts.properties` per i testi dei prompt. I valori sensibili arrivano da variabili d'ambiente o da `.env`; quelle dei moduli del framework (core e AI) hanno il prefisso `HX_`:

| Variabile | A cosa serve |
|---|---|
| HX_DB_USERNAME, HX_DB_PASSWORD (e HX_DB_HOST, HX_DB_PORT, HX_DB_NAME) | accesso al database |
| REPLICATE_API_TOKEN | generazione di immagini e video |
| HX_OPENROUTER_API_TOKEN | chat, miglioramento del prompt, analisi delle immagini |
| HX_OPENROUTER_MANAGEMENT_KEY | credito residuo di OpenRouter (facoltativa) |
| HX_OPENROUTER_CHAT_MODEL, HX_OPENROUTER_VISION_MODEL, HX_OPENROUTER_VISION_FALLBACK_MODEL | scelta dei modelli linguistici |
| SEARXNG_BASE_URL, HX_SEARXNG_USERNAME, HX_SEARXNG_PASSWORD | ricerca web della chat |
| HX_STORAGE_WEBDAV_URL, HX_STORAGE_WEBDAV_USERNAME, HX_STORAGE_WEBDAV_PASSWORD | storage su WebDAV |
| HX_STORAGE_WEBDAV_ENCRYPTION_KEY | cifratura dei file WebDAV, dei segreti e dei backup |
| HX_BACKUP_ENCRYPTION_KEY | chiave dei backup, se diversa da quella dello storage |
| HX_LOCK_RESET | recupero di un PIN dimenticato (da togliere dopo l'uso) |

Altri parametri utili: `app.chat.max-generations-per-turn`, `app.chat.max-vision-calls-per-turn`, `app.chat.history-max-turns` e `-chars`, `app.import.max-files`, `app.recovery.*` (recupero delle generazioni), `app.search.*` (ricerca), `app.training.*` (limiti e tempi dell'addestramento) e `storage.type`.

## Dati sul disco

Per impostazione predefinita le immagini finiscono in `./data/images`, il database di sviluppo in `./data/postgres`, la cache dei blob in `./data/cache` e i modelli in `./data/models`. Sono tutti fuori da git.

## Compilare

`mvn clean package` produce un jar eseguibile (Tailwind dal CDN). Con `mvn -Ptailwind clean package` si ottiene in più un CSS compilato e minificato, scaricando la CLI standalone di Tailwind (serve la rete, solo macOS e Linux). Il badge in basso a sinistra mostra ora e commit della build; da jar il commit si passa con `-Dbuild.commit=<hash>`.

## I test

`mvn test` richiede **Docker**: avvia un container PostgreSQL con pgvector usa e getta e non tocca mai il database di sviluppo. Nessun test chiama Replicate o OpenRouter. `mvn test -Dtest=ArchitectureTest` esegue solo le regole di architettura (non richiedono Docker).

## Dietro un reverse proxy

L'app si aspetta di stare dietro un proxy che termina TLS e inoltra in HTTP, con gli header standard: `X-Forwarded-Proto`, `X-Forwarded-Host`, `X-Forwarded-Port` sempre, e `X-Forwarded-Prefix` se l'app è esposta sotto un sottopercorso. Senza, redirect e link usano lo schema e l'indirizzo interni. Non esporre mai la porta dell'app direttamente su Internet.

La connessione `GET /events` (SSE) è tenuta aperta apposta: il proxy **non deve bufferizzarla** (`proxy_buffering off` su nginx, solo per quella rotta) e dovrebbe avere un `proxy_read_timeout` lungo. Se cade, il browser si riconnette da solo; un evento perso non è definitivo, perché il messaggio è già persistito.

## Backup e ripristino

Lo stesso jar fa il backup completo di database e file in un unico archivio cifrato:

```
java -jar target/spring-htmx-starter-*.jar export backup.dfb
java -jar target/spring-htmx-starter-*.jar import backup.dfb
java -jar target/spring-htmx-starter-*.jar import backup.dfb --replace
```

- Servono solo le credenziali di ciò che il comando tocca (il database e, con WebDAV, il server), lette da `.env` nella cartella da cui lanci il jar. I token di Replicate, OpenRouter e SearXNG **non servono** e il backup non li contiene.
- L'archivio è cifrato con `HX_BACKUP_ENCRYPTION_KEY` o, se manca, con la chiave dello storage; senza una chiave valida `export` si rifiuta (`--no-encrypt` produce un file in chiaro, sconsigliato). Per ripristinare serve la **stessa chiave**, da conservare fuori dal backup.
- Contiene tutte le tabelle (note manuali comprese, che esistono solo nell'indice) e i file referenziati dal database. Si può esportare da WebDAV e importare in locale.
- L'import vuole un database **vergine**; `--replace` azzera lo schema e **cancella i dati attuali**. Un backup di una versione più vecchia entra in un jar più nuovo; uno più nuovo del jar si rifiuta. Il server va fermato durante l'import.
- I segreti (token API e simili) si copiano cifrati con la chiave dello storage: se nel sistema di destinazione è diversa non si aprono, e l'import lo segnala nella campanella.

## Cosa gira da solo

All'avvio e a intervalli: il recupero delle generazioni e delle chat in sospeso, l'avanzamento dei training in corso (anche a pagina chiusa, ogni 30 secondi), le didascalie e i risultati dei training rimasti a metà, il controllo delle scadenze dei token, la riconciliazione dell'indice di ricerca. Tutto si spegne nei test. Per capire cosa è andato storto, la prima pagina è [Eventi](/system/events), poi i log del processo.

## PWA e service worker

L'app è installabile grazie a `hexa-pwa` (manifest, icone, service worker). Il worker è volutamente stretto: tiene in cache solo la pagina `/offline`, gli asset statici e gli script dei CDN del layout; l'HTML dinamico, le richieste non GET, gli eventi in tempo reale (`/events`) e le immagini passano sempre dalla rete, perché la stessa URL risponde in due modi (pagina o fragment htmx) e i binari sono serviti con Range ed ETag. Il nome della cache segue la build: ogni release invalida la precedente. Serve HTTPS (dietro un [reverse proxy](#dietro-un-reverse-proxy) con `X-Forwarded-*`); per cambiare le icone si mettono file con gli stessi nomi in `static/pwa/icons/` dell'app, e i colori della barra del browser si impostano con `app.pwa.theme-color` e `app.pwa.theme-color-dark`. Si spegne con `app.pwa.enabled=false`. Per l'utente vedi [Installare come app](../01-uso/13-installare-come-app.md).

## Blocco con PIN

Il blocco sta nel core (`core.lock`), non nella PWA. Il cancello è lato server: con il PIN impostato e la sessione bloccata passano solo la pagina di sblocco, gli asset statici e i path che le librerie dichiarano esenti (la shell della PWA); pagine, htmx, `/events` e `/images/**` rispondono con redirect o 401. Lo stato di sblocco è nella sessione HTTP e conta solo gli input veri dell'utente, inviati dal browser come «touch»: il polling automatico non tiene sveglia l'app. Il PIN è un hash PBKDF2 nella tabella `app_lock`, con rallentamento persistito dei tentativi sbagliati. Un PIN dimenticato si recupera con `app.lock.reset=true` all'avvio (da togliere dopo l'uso). Senza `hexa-oauth2` non c'è Spring Security e il PIN è l'unico accesso; protegge da un dispositivo incustodito o da un altro browser, non cifra i dati. Il backup include la tabella: ripristinarlo riporta il PIN del momento. Per l'utente vedi [Blocco con PIN](../01-uso/14-blocco-con-pin.md).

## Accesso con OAuth2

`hexa-oauth2` è un cancello di primo livello opzionale, nel suo jar (`org.dual.hexa.oauth2.login`): autentica con un provider OIDC e fa entrare solo chi è in una lista. È l'unico punto in cui c'è Spring Security, e la sua catena di filtri (`OAuthSecurityConfig`) è l'unica dell'app. Per l'utente vedi [Accesso con OAuth2](../01-uso/15-accesso-con-oauth2.md).

**Spento è invisibile.** La catena è statica e l'interruttore è un dato: la decisione di autorizzazione legge `IOAuthAccess.isEnabled()` a ogni richiesta (stato in memoria, nessun I/O). A cancello spento le intestazioni di default e il CSRF di Spring Security sono disabilitati e nessuna richiesta cambia risposta; un test lo fissa. A cancello acceso il CSRF è un controllo di stessa origine (`Sec-Fetch-Site`, altrimenti `Origin` contro l'host), non il token: il token romperebbe ogni POST di htmx, i `fetch` degli script e l'upload della maschera.

**Fallisce chiuso.** Acceso senza provider o senza ammessi, nessuno entra. Dalla UI il cancello si accende solo con un provider, un ammesso e un accesso di prova riuscito, e con il cancello acceso non si toglie l'ultima voce utile né l'ultimo provider. Il recupero è `HX_OAUTH2_RESET=true` all'avvio, che spegne il cancello e lo scrive negli eventi.

**Configurazione essenziale con variabili.** `HX_OAUTH2_PROVIDER` (`google` o vuoto), `HX_OAUTH2_ISSUER_URI`, `HX_OAUTH2_CLIENT_ID`, `HX_OAUTH2_CLIENT_SECRET`, `HX_OAUTH2_ALLOWED_EMAILS`, `HX_OAUTH2_ALLOWED_DOMAINS`, `HX_OAUTH2_ENABLED`, `HX_OAUTH2_RESET`. Definiscono un provider d'ambiente virtuale, in sola lettura, il cui segreto non viene mai salvato né scritto in log, eventi o modello; gli elenchi si sommano a quelli salvati. Il resto è un modulo di configurazione del core (`Oauth2ConfigModule`, sezione in `/settings`): l'interruttore, i provider come righe (con il segreto del client come campo `SECRET`, un segreto gestito di tipo `OAUTH2_CLIENT` visibile in `/secrets`) e gli elenchi degli ammessi, additivi con le variabili `HX_OAUTH2_ALLOWED_*`. `HX_OAUTH2_ENABLED=true` è autorevole: la UI non lo spegne. Un fragment del modulo mostra stato, checklist, indirizzo di ritorno, «Prova accesso» ed «Esci».

**La lista.** Si applica dentro l'autenticazione (`AllowlistOidcUserService`) prima che la sessione sia salvata, con i soli claim dell'id token. Le regole stanno in `application`, senza tipi di Spring Security: email normalizzata e `email_verified` obbligatorio, dominio uguale alla parte dopo l'ultima chiocciola (mai `endsWith`), legame di `issuer` e `subject` al primo accesso di un'email esatta.

**Provider dinamici.** `DynamicClientRegistrations` è il repository dei client: legge la discovery OIDC con il `RestClient.Builder` dell'app, controlla che l'issuer dichiarato coincida, tiene la registrazione in cache per dieci minuti e usa sempre PKCE. Un guasto si registra come evento di sorgente `OAUTH2`.

**Percorsi.** Passano senza accesso gli asset, la pagina di accesso, i flussi OIDC e i path di `ILockExemptPaths` (con `hexa-pwa`: manifest, service worker, pagina offline, icone). `/unlock` non è esente: prima l'accesso, poi il PIN. Chi non è autenticato riceve un redirect (navigazione), un 401 con `HX-Redirect` (htmx, tranne dalla pagina di accesso) o un 401 secco (SSE, immagini, fetch).

**Non verificato dal vero**: il login con Google o Microsoft, l'indirizzo di ritorno dietro un reverse proxy su sottopercorso e il comportamento dentro la PWA installata. Il flusso intero si prova solo contro un provider OIDC finto, mai uno vero.
