# spring-htmx-starter

Webapp snella: navigazione via HTML renderizzato dal server, arricchita
da htmx dove serve un aggiornamento parziale e da Alpine.js dove serve
stato locale. Nessuno step di build frontend.

L'app genera immagini (e video) via [Replicate](https://replicate.com),
le scarica e le salva (in locale o su WebDAV cifrato), offre una galleria
consultabile con prompt e parametri usati per ogni immagine, una chat
(`/deep-chat`) che puo' cercare sul web e generare, e una ricerca
semantica sull'archivio.

## Architettura e riuso come template

Il codice e' diviso in `core` (generico e riusabile: layout e componenti
UI, errori/retry remoti, eventi di sistema con campanella e toast, SSE,
token cifrati, storage dei binari) e `app` (specifico: generazione,
galleria, chat, LoRA, ricerca). Ogni sottosistema e' un esagono (domain,
application, port.in/out, adapter.in/out), con le regole imposte da
`ArchitectureTest`. Per le convenzioni vedi [`CLAUDE.md`](./CLAUDE.md); per
costruire un'altra webapp tenendo il core e sostituendo l'app vedi
[`docs/TEMPLATE.md`](./docs/TEMPLATE.md).

## Avvio

Richiede Maven installato (nessun wrapper incluso nello zip), Docker per
il database (PostgreSQL+pgvector: `cp .env.example .env`, imposta
`HX_DB_PASSWORD`, poi `docker compose up -d`) e un token API Replicate,
generabile su
[replicate.com/account/api-tokens](https://replicate.com/account/api-tokens):

```bash
export REPLICATE_API_TOKEN=r8_...
mvn -q install -DskipTests && mvn -pl deep-flux spring-boot:run
```

In alternativa, copia `.env.example` in `.env` (escluso da git), valorizza
`REPLICATE_API_TOKEN` e sourcizzalo prima dell'avvio — Spring Boot legge
il token dalle variabili d'ambiente del processo, non dal file `.env`
direttamente:

```bash
cp .env.example .env   # poi modifica .env col tuo token
set -a; source .env; set +a
mvn -q install -DskipTests && mvn -pl deep-flux spring-boot:run
```

Poi apri http://localhost:7070.

**Una nuova app su hexa**: `mvn archetype:generate -DarchetypeGroupId=org.dual -DarchetypeArtifactId=hexa-archetype ...` genera un'applicazione con menu laterale,
tema chiaro/scuro e, a scelta (`-DuseAi=true`), hexa-ai: vedi `docs/TEMPLATE.md`. Senza il token la navigazione normale
funziona comunque: la generazione fallirà con un errore chiaro finché
non lo imposti.

Le immagini generate finiscono in `./data/images` e i metadati (prompt,
modello, parametri) in PostgreSQL con l'estensione pgvector (usato anche per
la ricerca semantica). Per lo sviluppo basta `docker compose up -d`
(`compose.yaml`, credenziali `HX_DB_NAME`, `HX_DB_USERNAME` (obbligatori, nessun default nel core) e `HX_DB_PASSWORD` nel `.env`, vedi
`.env.example`); i dati stanno in `./data/postgres`, escluso da git. I test
(`mvn test`) richiedono Docker: usano un container pgvector usa-e-getta.

### Generare un'immagine

1. Vai su **Genera immagine** (menu *Crea*), o direttamente `/generations/new`. Per un video c'e' **Genera video**
   (`/generations/new?kind=video`), per ritoccare un'immagine **Modifica immagine** (`/generations/new?kind=edit`).
2. Scegli il modello dalla select (sono quelli censiti nel catalogo, tabella `replicate_model`): il form mostra i campi
   propri di quel modello (proporzioni, numero di immagini, seed, LoRA per `flux-dev-lora`, immagine sorgente per video/modifica...).
   Scrivi il prompt, se vuoi con **AI enhance** (riscrive la bozza con un LLM). "Version" e' facoltativa: per pinnare una
   versione specifica del modello, incolla l'hash.
3. Alla conferma compare il dettaglio `/generations/{id}` con un segnaposto ("Interrompi" per annullare); la pagina si
   aggiorna da sola (polling htmx ogni 2s) finche' la generazione non e' terminata.
4. A generazione riuscita il dettaglio mostra prompt, modello, parametri e costo stimato, con tutte le immagini in griglia
   (cancellabili una a una, con la stella dei preferiti e le azioni "Anima"/"Modifica"). Le generazioni riuscite stanno
   anche in **Galleria** (`/gallery`, tab Tutte/Preferiti) e, filtrate per tipo, nella **Ricerca** (`/search?type=generation`, voce **Generazioni** del menu).

In alternativa, la stessa cosa si fa conversando in **Deep Chat** (`/deep-chat`): l'assistente genera sempre col modello
scelto nel pannello impostazioni e l'esito arriva in chat via SSE.

## Backup e ripristino

Lo stesso jar eseguibile fa il backup completo del sistema (database + immagini/video, da `./data/images` o da WebDAV) in UN file, e lo ripristina:

```bash
mvn -DskipTests package
java -jar deep-flux/target/deep-flux-*.jar export backup.dfb            # crea backup.dfb (cifrato)
java -jar deep-flux/target/deep-flux-*.jar import backup.dfb            # su un database vergine
java -jar deep-flux/target/deep-flux-*.jar import backup.dfb --replace  # azzera lo schema esistente (CANCELLA i dati attuali)
```

- **Credenziali**: servono solo quelle di cio' che il comando tocca, lette dal `.env` della **directory da cui lanci il jar** (o da variabili d'ambiente), come per il server:
  `HX_DB_*` (database sorgente per `export`, di destinazione per `import`) e, se `storage.type=webdav`, `HX_STORAGE_WEBDAV_URL`, `HX_STORAGE_WEBDAV_USERNAME` e `HX_STORAGE_WEBDAV_PASSWORD`. Le credenziali di Replicate, OpenRouter
  e SearXNG **non servono** e il backup non le contiene (ne' contiene il `.env`).
- **Chiave**: l'archivio e' cifrato (AES-256-GCM) con `HX_BACKUP_ENCRYPTION_KEY`, che se manca vale `HX_STORAGE_WEBDAV_ENCRYPTION_KEY`; senza una chiave valida `export` si rifiuta (usa
  `--no-encrypt` per un file in chiaro, sconsigliato: contiene prompt, chat e immagini). Per ripristinare serve la **stessa chiave**: conservane una copia fuori dal backup. Le immagini
  stanno nell'archivio in chiaro (dentro la cifratura), quindi si puo' esportare da WebDAV e importare in locale, o su un altro WebDAV con un'altra chiave dello storage.
- **Segreti** (`/secrets`, token API e simili): si copiano cifrati con la chiave dello storage. Se sul nuovo sistema la chiave e' diversa non si aprono: l'import lo segnala (campanella) e vanno reinseriti.
- **Database di destinazione**: deve essere vergine (es. `docker compose up -d` appena creato). Un backup di una versione piu' vecchia dell'app si importa in un jar piu' nuovo (le migrazioni
  mancanti si applicano dopo il caricamento); uno piu' nuovo del jar si rifiuta.
- **Server fermo** durante l'import (obbligatorio) e, meglio, anche durante l'export: lo snapshot del database e' coerente anche a server acceso, ma i file creati o cancellati nel
  frattempo possono mancare (l'export li elenca come avvisi). Un import interrotto sui file si rilancia (quelli gia' scritti si saltano).

## Dietro un reverse proxy

L'app (`server.forward-headers-strategy: framework` in `application.yml`)
si aspetta di stare dietro un reverse proxy che termina TLS e inoltra le
richieste su HTTP semplice, impostando gli header standard:

- `X-Forwarded-Proto`, `X-Forwarded-Host`, `X-Forwarded-Port` — sempre,
  altrimenti l'app costruisce redirect e link con lo scheme/host interni
  invece di quelli pubblici.
- `X-Forwarded-Prefix` — solo se il proxy espone l'app sotto un path
  (es. `https://host/immagini/` mentre l'app gira alla radice), cosi'
  i link generati da Thymeleaf includono il prefisso.

Configurazione di default sia di nginx (`proxy_set_header X-Forwarded-*`
nei setup standard, es. Certbot) sia di Traefik/Caddy: di norma non
serve altro.

`/api/deep-chat` (il backend del Web Component `<deep-chat>`) non
aspetta piu' che un'immagine sia pronta: `ImageGenerationTool` avvia la
generazione e torna subito, il risultato arriva dopo via push (vedi
sotto) — nessun timeout esplicito da alzare per questa rotta.

`GET /events` (SSE, vedi `EventStreamController`/`PushService`)
e' invece una connessione tenuta aperta apposta, verso cui `/gallery` e
`/deep-chat` si registrano per ricevere il risultato di una generazione
non appena pronto. Il proxy davanti all'app deve:
- **non bufferizzare** questa rotta (`proxy_buffering off;` su nginx,
  posto specificamente su `location /events`), altrimenti gli eventi
  restano bufferizzati e non arrivano mai al browser;
- avere un `proxy_read_timeout` lungo, o accettare che la connessione
  cada periodicamente. Non e' un problema se cade: `EventSource` si
  riconnette da solo, e un eventuale evento perso nella finestra di
  disconnessione non e' definitivo — il messaggio e' comunque gia'
  persistito, ricompare al primo reload/cambio conversazione.

Senza `hexa-oauth2` non ci sono sessioni/cookie ne' Spring Security in questa app
(il blocco con PIN usa la sessione HTTP, ma nessuno stato da propagare oltre il
cookie). Con l'accesso OAuth2 acceso serve in piu' `server.forward-headers-strategy=framework`
(e `X-Forwarded-Prefix` per un sottopercorso), o l'indirizzo di ritorno calcolato non
e' quello pubblico.

## Accesso con OAuth2 (opzionale)

`hexa-oauth2` aggiunge l'accesso con un provider OAuth2/OIDC (Google, Microsoft, Keycloak...) e una lista di
utenti ammessi (email esatte e domini): essere autenticati dal provider non basta. E' **spento di default**
(l'app resta aperta come prima). Per essere operativi bastano poche variabili nel `.env` (`HX_OAUTH2_PROVIDER`,
`HX_OAUTH2_CLIENT_ID`, `HX_OAUTH2_CLIENT_SECRET`, `HX_OAUTH2_ALLOWED_EMAILS` / `HX_OAUTH2_ALLOWED_DOMAINS`, vedi
`deep-flux/.env.example`); il resto (altri provider, utenti, interruttore) e' in `/oauth2`, menu «Gestione».
Dalla UI il cancello si accende solo dopo un accesso di prova riuscito; se ci si chiude fuori, `HX_OAUTH2_RESET=true`
all'avvio lo spegne. Nelle app generate dall'archetype: `-DuseOauth2=true`.

## Dettagli

Per stack, convenzioni e come estendere il progetto vedi [`CLAUDE.md`](./CLAUDE.md).

