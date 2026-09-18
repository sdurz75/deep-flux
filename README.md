# spring-htmx-starter

Webapp snella: navigazione via HTML renderizzato dal server, arricchita
da htmx dove serve un aggiornamento parziale e da Alpine.js dove serve
stato locale. Nessuno step di build frontend.

Oltre alle demo dello starter, l'app genera immagini via
[Replicate](https://replicate.com), le scarica e le salva localmente, e
offre una galleria consultabile con prompt e parametri usati per ogni
immagine.

## Avvio

Richiede Maven installato (nessun wrapper incluso nello zip) e un token
API Replicate, generabile su
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
modello, parametri) in un DB H2 locale sotto `./data/db` — entrambi
esclusi da git.

### Generare un'immagine

1. Vai su **Genera immagine** in nav, o direttamente `/generations/new`.
2. Inserisci un modello Replicate nella forma `owner/nome` (es.
   `black-forest-labs/flux-schnell`), il prompt, ed eventuali parametri
   extra come JSON (es. `{"aspect_ratio": "1:1", "num_outputs": 1}`).
   Per pinnare una versione specifica del modello, compila anche
   "Version" con l'hash.
3. La pagina mostra lo stato aggiornandosi da sola (polling htmx ogni
   2s) finché l'immagine non è pronta.
4. Le immagini completate compaiono in **Galleria** (`/gallery`), con
   una pagina di dettaglio per ciascuna che mostra prompt, modello e
   parametri usati.

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

Un solo timeout da alzare esplicitamente: `/api/deep-chat` (il backend
del Web Component `<deep-chat>`) puo' restare in attesa fino a ~90s
mentre l'assistente genera un'immagine su Replicate (vedi
`ImageGenerationTool.WAIT_TIMEOUT`), oltre al tempo della chiamata LLM
stessa. Il timeout di lettura/proxy sulla rotta deve essere piu' alto
di quello (es. `proxy_read_timeout 120s;` su nginx), altrimenti il
proxy chiude la connessione prima che l'assistente risponda anche se la
generazione va a buon fine.

Non essendoci sessioni/cookie ne' Spring Security in questa app, non
c'e' altro stato lato server da propagare attraverso il proxy.

## Dettagli

Per stack, convenzioni e come estendere il progetto vedi [`CLAUDE.md`](./CLAUDE.md).
