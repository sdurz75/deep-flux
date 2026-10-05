# Operatività

Come si avvia, si configura, si pubblica e si salva il sistema.

## Primo avvio

Servono Java 21, Maven (non c'è il wrapper), Docker per il database e un token API di Replicate.

1. Copia `.env.example` in `.env` (escluso da git) e imposta almeno `DB_PASSWORD` e `REPLICATE_API_TOKEN`. L'app legge `.env` dalla cartella da cui parte il processo.
2. Avvia il database: `docker compose up -d`.
3. Avvia l'app: `mvn spring-boot:run`.
4. Al primo avvio con la ricerca attiva, l'app scarica il modello di embedding (circa 120 megabyte) in `./data/models`.

Senza il token di Replicate la navigazione funziona comunque; la generazione fallisce con un messaggio chiaro. La porta su cui ascolta è la proprietà `server.port` in `core.yml`.

## La configurazione

Due file, con chiavi disgiunte: `application.yml` per l'app (Spring AI, Replicate, SearXNG, `app.*`) e `core.yml` per il core (web, database, eventi, segreti, storage), più `prompts.properties` per i testi dei prompt. I valori sensibili arrivano da variabili d'ambiente o da `.env`:

| Variabile | A cosa serve |
|---|---|
| DB_USERNAME, DB_PASSWORD (e DB_HOST, DB_PORT, DB_NAME) | accesso al database |
| REPLICATE_API_TOKEN | generazione di immagini e video |
| OPENROUTER_API_TOKEN | chat, miglioramento del prompt, analisi delle immagini |
| OPENROUTER_MANAGEMENT_KEY | credito residuo di OpenRouter (facoltativa) |
| OPENROUTER_CHAT_MODEL, OPENROUTER_VISION_MODEL, OPENROUTER_VISION_FALLBACK_MODEL | scelta dei modelli linguistici |
| SEARXNG_BASE_URL, SEARXNG_USERNAME, SEARXNG_PASSWORD | ricerca web della chat |
| STORAGE_WEBDAV_URL, STORAGE_WEBDAV_USERNAME, STORAGE_WEBDAV_PASSWORD | storage su WebDAV |
| STORAGE_WEBDAV_ENCRYPTION_KEY | cifratura dei file WebDAV, dei token e dei backup |
| BACKUP_ENCRYPTION_KEY | chiave dei backup, se diversa da quella dello storage |

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
- L'archivio è cifrato con `BACKUP_ENCRYPTION_KEY` o, se manca, con la chiave dello storage; senza una chiave valida `export` si rifiuta (`--no-encrypt` produce un file in chiaro, sconsigliato). Per ripristinare serve la **stessa chiave**, da conservare fuori dal backup.
- Contiene tutte le tabelle (note manuali comprese, che esistono solo nell'indice) e i file referenziati dal database. Si può esportare da WebDAV e importare in locale.
- L'import vuole un database **vergine**; `--replace` azzera lo schema e **cancella i dati attuali**. Un backup di una versione più vecchia entra in un jar più nuovo; uno più nuovo del jar si rifiuta. Il server va fermato durante l'import.
- I token API si copiano cifrati con la chiave dello storage: se nel sistema di destinazione è diversa non si aprono, e l'import lo segnala nella campanella.

## Cosa gira da solo

All'avvio e a intervalli: il recupero delle generazioni e delle chat in sospeso, l'avanzamento dei training in corso (anche a pagina chiusa, ogni 30 secondi), le didascalie e i risultati dei training rimasti a metà, il controllo delle scadenze dei token, la riconciliazione dell'indice di ricerca. Tutto si spegne nei test. Per capire cosa è andato storto, la prima pagina è [Eventi](/system/events), poi i log del processo.
