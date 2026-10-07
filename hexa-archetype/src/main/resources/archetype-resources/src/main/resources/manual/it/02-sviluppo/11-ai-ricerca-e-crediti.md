# Ricerca e crediti (hexa-ai)

Anche questa pagina riguarda le app con `hexa-ai`.

## Ricerca semantica

Usa lo stesso PostgreSQL con pgvector e embedding locali (`multilingual-e5-small`, 384 dimensioni, scaricato in `data/models`). Non c'è un indice approssimato di proposito: i punteggi sono compressi, quindi si lavora per classifica (top-K), non per soglie fisse.

## Rendere ricercabili i propri dati

Un sottosistema dell'app implementa [`ISearchableSource`](https://sdurz75.github.io/deep-flux/apidocs/org/hexa/core/search/port/in/ISearchableSource.html): dichiara i `types()` dei documenti e li fornisce con `documents()`. L'indicizzazione è una riconciliazione idempotente in background: indicizza i documenti nuovi o cambiati e rimuove quelli la cui riga non esiste più. `citation` dice come presentare un risultato. I metadata riservati sono `contentHash`, `embeddingModel` e `indexedAt`. Le note libere dell'utente sono gestite da [`IArchiveNotes`](https://sdurz75.github.io/deep-flux/apidocs/org/hexa/core/search/port/in/IArchiveNotes.html).

## Cercare

[`IArchiveSearch`](https://sdurz75.github.io/deep-flux/apidocs/org/hexa/core/search/port/in/IArchiveSearch.html) offre la ricerca sul testo; la pagina `/search` è del core, con uno slot dell'host in `app.search.host-fragment`. Con `app.search.enabled=false` indice, scheduler ed embedding sono spenti (utile nei test).

## Crediti

La barra in basso mostra i crediti dei servizi a pagamento. L'app aggiunge le proprie righe implementando [`ICreditSource`](https://sdurz75.github.io/deep-flux/apidocs/org/hexa/core/credits/port/in/ICreditSource.html) (un elenco di `CreditLine`); [`ICredits`](https://sdurz75.github.io/deep-flux/apidocs/org/hexa/core/credits/port/in/ICredits.html) le aggrega. Il rendering non fa chiamate remote: il chip carica `GET /credits/bar` con htmx.

## Riferimento API

[`org.hexa.core.search.port.in`](https://sdurz75.github.io/deep-flux/apidocs/org/hexa/core/search/port/in/package-summary.html), [`org.hexa.core.credits.port.in`](https://sdurz75.github.io/deep-flux/apidocs/org/hexa/core/credits/port/in/package-summary.html).
