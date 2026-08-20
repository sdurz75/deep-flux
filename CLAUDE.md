# CLAUDE.md

Guida di riferimento per lavorare su questo repository. Leggerla prima di
aggiungere pagine, endpoint o dipendenze: le scelte qui sotto non sono
casuali, sono vincoli deliberati per mantenere il progetto snello.

## Filosofia

Hypermedia-first, non SPA. Il server resta la fonte di verità dello stato
dell'applicazione e restituisce HTML, non JSON. Il client arricchisce
quell'HTML, non lo sostituisce.

- **Navigazione** → HTML renderizzato dal server (Spring MVC + Thymeleaf).
- **Aggiornamenti parziali / navigazione senza reload** → htmx.
- **Micro-interattività locale** (toggle, dropdown, form state, validazione
  immediata) → Alpine.js.
- **Componenti davvero complessi** (editor, diff viewer, grafici) → se e
  quando servono, un Web Component isolato montato su un singolo `<div>`,
  non un framework SPA per l'intera app.
- **Theming** → CSS Custom Properties native, nessun preprocessore.

Zero step di build frontend: niente npm/webpack/vite/esbuild. htmx e
Alpine.js sono caricati da CDN in `fragments/layout.html`. Se un giorno
serve vendorizzarli offline, basta scaricare i due file JS in
`static/js/` e cambiare i due `<script src="...">` — nessun altro impatto.

### Cosa NON introdurre senza una ragione concreta

- **Spring WebFlux** — nessun beneficio reale per una webapp a navigazione
  prevalentemente server-rendered; aggiunge solo complessità (Mono/Flux).
  Restare su Spring MVC classico (`spring-boot-starter-web`).
- **React/Vue/Angular come framework applicativo** — duplicherebbe la
  gestione di routing/stato che il server già fa. Se serve un widget
  isolato, montarlo come Web Component su un `<div>` mirato, non riscrivere
  la navigazione.
- **thymeleaf-layout-dialect** o altre librerie di layout — il pattern
  Thymeleaf "vanilla" con fragment parametrizzati (vedi sotto) copre il
  100% dei casi qui e non aggiunge una dipendenza.
- **Tailwind / preprocessori CSS** — richiedono comunque un passo di
  build; le custom properties native bastano per questo scopo.

## Stack

| Livello | Scelta | Perché |
|---|---|---|
| Backend | Spring Boot 3.x, Spring MVC | Coerente con lo stack Spring esistente, nessun context-switch |
| Template engine | Thymeleaf | Fragment nativi, integrazione naturale con Spring MVC |
| Navigazione parziale | htmx (via CDN) | Markup dichiarativo via attributi, niente build |
| Micro-interattività | Alpine.js (via CDN) | Stato dichiarato inline, niente build |
| Theming | CSS Custom Properties | Cambio tema = cambio attributo `data-theme`, zero ricalcolo server |
| Persistenza | Spring Data JPA + H2 file-based | Metadata delle generazioni (prompt/parametri/stato); DB embedded su file locale, zero server esterno |
| Client HTTP verso Replicate | `RestClient` (Spring Framework 6.1+) | Sincrono, già incluso in `spring-boot-starter-web`: nessuna dipendenza WebFlux/reactor |
| Build | Maven | — |
| Java | 21 | LTS |

Il package radice del codice applicativo è `org.dual.replicate`.

## Struttura del progetto

```
src/main/java/org/dual/replicate/
  Application.java              # entry point Spring Boot
  controller/
    HomeController.java         # pagina intera, esempio minimo
    ItemsController.java        # pattern "load more" (paginazione incrementale)
    SearchController.java       # pattern "stessa URL, due risposte"
    GenerationController.java   # crea una generazione + polling htmx dello stato
    GalleryController.java      # galleria (load more) + dettaglio singola immagine
  domain/
    Generation.java             # entity JPA: prompt, modello, parametri, stato, file immagine
    GenerationStatus.java
  repository/
    GenerationRepository.java
  replicate/
    ReplicateClient.java        # wrapper RestClient sulle API Replicate
    PredictionResponse.java
    ReplicateException.java
  service/
    GenerationService.java      # crea la prediction, fa avanzare lo stato, orchestra il download
    ImageStorageService.java    # scrive i file immagine su storage.images-dir
  config/
    StorageConfig.java          # espone storage.images-dir come /images/**

src/main/resources/
  application.yml
  templates/
    index.html, items.html, search.html   # pagine demo starter
    generate.html                # form nuova generazione
    generation-status.html       # pagina di stato/polling di una generazione
    gallery.html                 # galleria (load more)
    gallery-detail.html          # dettaglio di una generazione
    fragments/
      layout.html                # shell HTML condivisa (head, header, footer)
      items.html, search.html    # fragment riusabili delle demo starter
      generate-form.html         # fragment del form (riusato anche per mostrare errori)
      generation.html            # fragment di stato di una generazione (polling)
      gallery.html               # fragment card + load more della galleria
  static/
    css/theme.css                # tutti i design token e gli stili
```

Le immagini generate e il DB H2 vivono in `./data/` (fuori da git, vedi
`.gitignore`), non sotto `static/`: sono stato applicativo prodotto a
runtime, non asset del progetto.

## Pattern Thymeleaf: layout parametrizzato

Nessuna libreria esterna. Ogni pagina si "sostituisce" con la chiamata al
fragment `layout`, passando il proprio `<title>` e il proprio `<main>`
come argomenti:

```html
<!DOCTYPE html>
<html lang="it" xmlns:th="http://www.thymeleaf.org"
      th:replace="~{fragments/layout :: layout(~{::title}, ~{::main})}">
<head>
    <title>Titolo pagina</title>
</head>
<body>
<main>
    <!-- contenuto -->
</main>
</body>
</html>
```

`fragments/layout.html` riceve questi due blocchi e li inserisce al posto
giusto (`<title th:replace="${title}">` nell'head, `<main th:replace="${content}">`
nel body). Per creare una nuova pagina: copiare questo scheletro, non
serve toccare `layout.html`.

## Pattern controller: quando restituire fragment vs pagina intera

Regola: **stessa URL, due risposte**, distinguendo in base all'header
`HX-Request` che htmx aggiunge automaticamente a ogni sua richiesta.

```java
@GetMapping
public String search(@RequestParam(defaultValue = "") String q,
                      @RequestHeader(value = "HX-Request", required = false) String hxRequest,
                      Model model) {
    // ... popolare il model ...
    boolean isHtmxRequest = "true".equalsIgnoreCase(hxRequest);
    return isHtmxRequest ? "fragments/search :: results" : "search";
}
```

Vantaggi di questo pattern rispetto ad avere due endpoint separati:

- un solo URL, condivisibile/bookmarkabile, che funziona sia con
  JavaScript disabilitato (fallback a navigazione piena) sia con htmx;
- nessuna duplicazione di markup: il fragment dei risultati è lo stesso
  sia che venga incorporato nella pagina intera sia che venga restituito
  da solo.

Esempi già implementati da copiare:

- `SearchController` — ricerca live, `hx-trigger="input changed delay:300ms"`.
- `ItemsController` — paginazione "load more", il pulsante si sostituisce
  con `hx-target="this" hx-swap="outerHTML"` (o target esplicito + swap
  `outerHTML`/`beforeend` a seconda del caso).

## Convenzione: attributi `hx-*` dinamici in Thymeleaf

Per un attributo `hx-*` con valore statico, scriverlo come HTML puro:

```html
<input hx-get="/search" hx-trigger="input changed delay:300ms, search" hx-target="#search-results-list">
```

Per un valore dinamico (es. un parametro calcolato dal model), usare il
prefisso `th:` — Thymeleaf lo riconosce come "generic attribute setter"
anche per attributi non standard come `hx-get`:

```html
<button th:hx-get="@{/items(page=${nextPage})}" hx-target="#load-more" hx-swap="outerHTML">
```

## Convenzione: theming

Tutti i colori/spaziature/radius vivono come custom property in
`static/css/theme.css`, dichiarati in `:root` e sovrascritti in
`[data-theme="dark"]`. Nessuna regola CSS deve usare un colore hardcoded:
sempre `var(--color-xxx)`.

Per aggiungere un tema (es. "high-contrast"):

1. Aggiungere un blocco `[data-theme="high-contrast"] { --color-bg: ...; }`
   in `theme.css` con tutte le variabili del blocco `:root`.
2. Aggiungere un bottone nello `theme-switch` in `fragments/layout.html`
   (`@click="theme = 'high-contrast'"`).

Non serve toccare altro: ogni componente legge già le variabili, non i
valori.

Il boot dello script inline in `layout.html` (prima del `<link>` al CSS)
è lì per evitare il FOUC (flash of unstyled content) al primo caricamento:
legge `localStorage`, risolve `auto` in `light`/`dark` in base a
`prefers-color-scheme`, e setta `data-theme` sull'`<html>` PRIMA che il
CSS venga applicato. Alpine prende il controllo dello stato subito dopo,
ma parte già dal valore corretto — non spostarlo più in basso nella pagina.

## Comandi utili

Nessun Maven Wrapper incluso: serve Maven installato sulla macchina
(`mvn -v` per verificare). Se preferisci il wrapper, generalo con
`mvn wrapper:wrapper` e committa i file risultanti.

```bash
mvn spring-boot:run        # avvio in sviluppo (Thymeleaf cache=false, reload live)
mvn test                   # test
mvn clean package          # build del jar eseguibile
```

## Checklist per aggiungere una nuova pagina/feature

1. Serve solo navigazione? → nuovo controller + nuovo template pagina
   con il pattern layout parametrizzato sopra. Basta.
2. Serve un aggiornamento parziale (ricerca live, paginazione, form senza
   reload)? → estrarre la porzione riusabile in `fragments/<nome>.html`,
   far restituire al controller quel fragment quando `HX-Request` è
   presente, la pagina intera altrimenti (vedi `SearchController`).
3. Serve solo interattività locale, nessuna chiamata al server (aprire/
   chiudere un pannello, validare un campo)? → `x-data`/`x-show`/`x-on`
   di Alpine.js, direttamente nel template, senza controller dedicato.
4. Serve davvero un componente complesso stateful (editor, canvas,
   grafico)? → valutare un Web Component isolato prima di introdurre un
   framework SPA per l'intera app.
