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

## Dettagli

Per stack, convenzioni e come estendere il progetto vedi [`CLAUDE.md`](./CLAUDE.md).
