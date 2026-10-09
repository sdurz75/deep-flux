# Ricerca e crediti (hexa-ai)

Anche questa pagina riguarda le app con `hexa-ai`.

## Ricerca semantica

Usa lo stesso PostgreSQL con pgvector e embedding locali (`multilingual-e5-small`, 384 dimensioni, scaricato in `data/models`). Non c'è un indice approssimato di proposito: i punteggi sono compressi, quindi si lavora per classifica (top-K), non per soglie fisse.

## Rendere ricercabili i propri dati

Un sottosistema dell'app implementa [`ISearchableSource`](https://git.trenolab.com/a.duca/hexa/src/branch/main/hexa-ai/src/main/java/org/dual/hexa/ai/search/port/in/ISearchableSource.java): dichiara i `types()` dei documenti e li fornisce con `documents()`. L'indicizzazione è una riconciliazione idempotente in background: indicizza i documenti nuovi o cambiati e rimuove quelli la cui riga non esiste più. `citation` dice come presentare un risultato. I metadata riservati sono `contentHash`, `embeddingModel` e `indexedAt`. Le note libere dell'utente sono gestite da [`IArchiveNotes`](https://git.trenolab.com/a.duca/hexa/src/branch/main/hexa-ai/src/main/java/org/dual/hexa/ai/search/port/in/IArchiveNotes.java).

## Cercare

[`IArchiveSearch`](https://git.trenolab.com/a.duca/hexa/src/branch/main/hexa-ai/src/main/java/org/dual/hexa/ai/search/port/in/IArchiveSearch.java) offre la ricerca sul testo; la pagina `/search` è del core, con uno slot dell'host in `app.search.host-fragment`. Con `app.search.enabled=false` indice, scheduler ed embedding sono spenti (utile nei test).

## Crediti

La barra in basso mostra i crediti dei servizi a pagamento. L'app aggiunge le proprie righe implementando [`ICreditSource`](https://git.trenolab.com/a.duca/hexa/src/branch/main/hexa-ai/src/main/java/org/dual/hexa/ai/credits/port/in/ICreditSource.java) (un elenco di `CreditLine`); [`ICredits`](https://git.trenolab.com/a.duca/hexa/src/branch/main/hexa-ai/src/main/java/org/dual/hexa/ai/credits/port/in/ICredits.java) le aggrega. Il rendering non fa chiamate remote: il chip carica `GET /credits/bar` con htmx.

## Riferimento API

[`org.dual.hexa.ai.search.port.in`](https://git.trenolab.com/a.duca/hexa/src/branch/main/hexa-ai/src/main/java/org/dual/hexa/ai/search/port/in), [`org.dual.hexa.ai.credits.port.in`](https://git.trenolab.com/a.duca/hexa/src/branch/main/hexa-ai/src/main/java/org/dual/hexa/ai/credits/port/in).
