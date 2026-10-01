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
`DB_PASSWORD`, poi `docker compose up -d`) e un token API Replicate,
generabile su
[replicate.com/account/api-tokens](https://replicate.com/account/api-tokens):

```bash
export REPLICATE_API_TOKEN=r8_...
mvn spring-boot:run
```

In alternativa, copia `.env.example` in `.env` (escluso da git), valorizza
`REPLICATE_API_TOKEN` e sourcizzalo prima dell'avvio — Spring Boot legge
il token dalle variabili d'ambiente del processo, non dal file `.env`
direttamente:

```bash
cp .env.example .env   # poi modifica .env col tuo token
set -a; source .env; set +a
mvn spring-boot:run
```

Poi apri http://localhost:8080. Senza il token la navigazione normale
funziona comunque: la generazione fallirà con un errore chiaro finché
non lo imposti.

Le immagini generate finiscono in `./data/images` e i metadati (prompt,
modello, parametri) in PostgreSQL con l'estensione pgvector (usato anche per
la ricerca semantica). Per lo sviluppo basta `docker compose up -d`
(`compose.yaml`, credenziali `DB_USERNAME`/`DB_PASSWORD` nel `.env`, vedi
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
   anche in **Galleria** (`/gallery`, tab Tutte/Preferiti) e tutte, anche quelle fallite, in **Generazioni** (`/generations`).

In alternativa, la stessa cosa si fa conversando in **Deep Chat** (`/deep-chat`): l'assistente genera sempre col modello
scelto nel pannello impostazioni e l'esito arriva in chat via SSE.

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

Non essendoci sessioni/cookie ne' Spring Security in questa app, non
c'e' altro stato lato server da propagare attraverso il proxy.

## Dettagli

Per stack, convenzioni e come estendere il progetto vedi [`CLAUDE.md`](./CLAUDE.md).

