# hexa

Librerie per webapp snelle: navigazione via HTML renderizzato dal server (Spring MVC + Thymeleaf), htmx dove serve un aggiornamento parziale,
Alpine.js dove serve stato locale. Nessuno step di build frontend, architettura esagonale per sottosistema.

| Modulo | Cosa da' |
|---|---|
| `hexa-core` | Base: layout e kit UI, errori/retry remoti, eventi di sistema con campanella e toast, SSE, segreti cifrati, storage dei binari (locale o WebDAV cifrato), backup/ripristino, manuale online, blocco con PIN, pagina `/settings` |
| `hexa-ai` | Opzionale sopra il core: chiamate LLM e visione, chat (`/deep-chat`), ricerca semantica (pgvector), crediti OpenRouter |
| `hexa-pwa` | Opzionale sopra il core: manifest, service worker e shell offline per rendere l'app installabile |
| `hexa-oauth2` | Opzionale sopra il core: accesso con OAuth2/OIDC e lista degli utenti ammessi |
| `hexa-bom` | Versioni allineate dei moduli, da importare in un host |
| `hexa-test-support` | Container PostgreSQL per i test e `HexaArchitectureRules` (regole di layering per un host qualunque) |
| `hexa-integration-tests` | Test di integrazione e di architettura delle librerie su un host di prova senza dominio (non pubblicato) |
| `hexa-archetype` | Genera una nuova app su hexa (`-DuseAi=true`, `-DusePwa=true`, `-DuseOauth2=true`) |

## Documentazione

| Cosa | Dove | Per chi |
|---|---|---|
| **Convenzioni e vincoli** (riferimento di dettaglio, aggiornato col codice) | [`CLAUDE.md`](./CLAUDE.md) (per agenti AI anche [`AGENTS.md`](./AGENTS.md); indice per i tool: [`llms.txt`](./llms.txt)) | chi modifica il codice, umani e agenti |
| **Usare hexa come base di una nuova app** (archetype, proprieta', cosa si tiene) | [`docs/TEMPLATE.md`](./docs/TEMPLATE.md) | chi crea un'altra webapp |
| **Guida dello sviluppatore delle app generate** (gruppo `02-sviluppo` del manuale incluso nell'archetype) | [`hexa-archetype/.../manual/it/02-sviluppo/`](./hexa-archetype/src/main/resources/archetype-resources/src/main/resources/manual/it/02-sviluppo/01-introduzione.md) | chi sviluppa un'app su hexa |
| **API pubbliche** (indice delle porte `port.in` e delle SPI di hexa-core, hexa-ai, hexa-pwa, hexa-oauth2, con link ai sorgenti commentati) | [`docs/API.md`](./docs/API.md) | chi usa le porte `port.in` |

Niente javadoc da pubblicare: la documentazione delle API e' nei commenti dei sorgenti, che il repository mostra con i link di [`docs/API.md`](./docs/API.md).

## Una nuova app su hexa

```bash
mvn archetype:generate -DarchetypeGroupId=org.dual -DarchetypeArtifactId=hexa-archetype ...
```

genera un'applicazione con menu laterale, tema chiaro/scuro e, a scelta, hexa-ai, hexa-pwa e hexa-oauth2: vedi [`docs/TEMPLATE.md`](./docs/TEMPLATE.md).

## Build e test

Richiede Maven (nessun wrapper incluso) e Docker (i test usano un container pgvector usa-e-getta):

```bash
mvn -q install -DskipTests   # installa le librerie nel repository locale
mvn test                     # test di tutti i moduli
```

Solo i test di architettura (senza Docker):

```bash
mvn test -pl hexa-integration-tests -am -Dtest=ArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false
```

## Operativita' di un host

Queste funzioni sono delle librerie e valgono per ogni app che le include.

### Backup e ripristino

Lo stesso jar eseguibile dell'host fa il backup completo (database + binari, da disco o da WebDAV) in UN file e lo ripristina:

```bash
java -jar app.jar export backup.dfb            # crea backup.dfb (cifrato)
java -jar app.jar import backup.dfb            # su un database vergine
java -jar app.jar import backup.dfb --replace  # azzera lo schema esistente (CANCELLA i dati attuali)
```

- **Credenziali**: servono solo `HX_DB_*` e, con `storage.type=webdav`, `HX_STORAGE_WEBDAV_URL|USERNAME|PASSWORD`, lette dal `.env` della directory da cui si lancia il jar o dall'ambiente.
- **Chiave**: l'archivio e' cifrato (AES-256-GCM) con `HX_BACKUP_ENCRYPTION_KEY`, che se manca vale `HX_STORAGE_WEBDAV_ENCRYPTION_KEY`; senza chiave `export` si rifiuta (`--no-encrypt` per un file in chiaro, sconsigliato). Per ripristinare serve la stessa chiave.
- **Segreti** (`/secrets`): si copiano cifrati con la chiave dello storage; con una chiave diversa non si aprono (l'import lo segnala) e vanno reinseriti.
- **Database di destinazione**: vergine. Un backup piu' vecchio entra in un jar piu' nuovo (le migrazioni mancanti si applicano dopo il caricamento), non viceversa.
- **Server fermo** durante l'import (obbligatorio) e, meglio, anche durante l'export.

### Dietro un reverse proxy

L'host (`server.forward-headers-strategy: framework`) si aspetta un reverse proxy che termina TLS e imposta `X-Forwarded-Proto`, `X-Forwarded-Host`,
`X-Forwarded-Port` (sempre) e `X-Forwarded-Prefix` (solo se l'app sta sotto un sottopercorso). Di norma e' gia' la configurazione di nginx, Traefik e Caddy.

`GET /events` (SSE) e' una connessione tenuta aperta apposta: il proxy **non deve bufferizzarla** (`proxy_buffering off;` su `location /events` in nginx) e deve avere un
`proxy_read_timeout` lungo, o accettare che cada: `EventSource` si riconnette da solo.

### Accesso con OAuth2 (opzionale)

`hexa-oauth2` aggiunge l'accesso con un provider OAuth2/OIDC (Google, Microsoft, Keycloak...) e una lista di utenti ammessi (email esatte e domini). E' **spento di default**.
Per essere operativo bastano `HX_OAUTH2_PROVIDER`, `HX_OAUTH2_CLIENT_ID`, `HX_OAUTH2_CLIENT_SECRET`, `HX_OAUTH2_ALLOWED_EMAILS` / `HX_OAUTH2_ALLOWED_DOMAINS`; il resto
(altri provider, utenti, interruttore) e' in `/settings`, scheda «Accesso OAuth2». Dalla UI il cancello si accende solo dopo un accesso di prova riuscito; se ci si chiude fuori,
`HX_OAUTH2_RESET=true` all'avvio lo spegne. Con l'accesso acceso serve `server.forward-headers-strategy=framework`. Nelle app generate dall'archetype: `-DuseOauth2=true`.

## Dettagli

Per stack, convenzioni e come estendere il progetto vedi [`CLAUDE.md`](./CLAUDE.md).
