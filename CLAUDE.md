# CLAUDE.md

Guida di riferimento per lavorare su questo repository. Leggerla prima di
aggiungere pagine, endpoint o dipendenze: le scelte qui sotto non sono
casuali, sono vincoli deliberati per mantenere il progetto snello.

## Scopo

L'applicazione serve a:

1. **Generare immagini con l'ausilio di un chatbot** — `/deep-chat`:
   descrivi cosa vuoi, l'assistente puo' cercare sul web per informarsi
   (`WebSearchTool`, via SearXNG) e generare l'immagine su Replicate
   (`ImageGenerationTool`) col modello scelto nel combobox o un altro se
   richiesto esplicitamente in chat. `/generations/new` resta la via
   diretta (form, senza chatbot) per chi vuole specificare modello/
   parametri a mano.
2. **Indicizzare le immagini generate e renderle reperibili/visualizzabili
   tramite un archivio** — ogni generazione (chatbot o form diretto)
   diventa una riga `Generation`, consultabile in `/gallery`.
3. **Mantenere una storia delle conversazioni e poterle riprendere in
   futuro** — `/deep-chat` persiste ogni turno lato server (`ChatMessage`/
   `ChatMessageRepository`, migrazione V3) e ripristina la cronologia ad
   ogni apertura della pagina (`DeepChatController` la passa al template
   come JSON, deep-chat la carica via `initialMessages`). Un'unica
   conversazione continua, non multi-utente (come il resto dell'app:
   `Generation` non ha un owner) — niente tabella "conversazione"
   separata come nello schema rimosso in V2 (`CHAT_CONVERSATION`/
   `CHAT_MESSAGE`, legato a una chat di rifinitura prompt ormai
   eliminata): qui basta l'elenco messaggi, azzerabile per intero dal
   bottone "Nuova conversazione" in UI (`POST /api/deep-chat/reset`).

Ulteriori evoluzioni seguiranno, ma sempre pertinenti a questi tre punti:
non aggiungere feature (pagine demo, integrazioni, pattern) che non
servono direttamente a generare, archiviare o conversare sulle immagini.
Se un domani serve dimostrare un pattern htmx/Alpine non ancora coperto
dal codice reale, farlo aggiungendolo a una feature vera, non con una
pagina demo isolata (le pagine demo starter — "Load more", "Search",
chat di rifinitura prompt — sono state rimosse per questo).

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
- **Theming** → CSS Custom Properties native, nessun preprocessore. Le
  utility Tailwind (vedi sotto) non sostituiscono questo sistema: servono
  solo per lo stile dei componenti Pines UI copiati in pagina, il resto
  del sito resta su `theme.css`/`var(--color-xxx)`.

Zero step di build frontend: niente npm/webpack/vite/esbuild. htmx,
Alpine.js e Tailwind (Play CDN, vedi sotto) sono caricati da CDN in
`fragments/layout.html`. Se un giorno serve vendorizzarli offline, basta
scaricare i file JS in `static/js/` e cambiare i `<script src="...">` —
nessun altro impatto.

### Cosa NON introdurre senza una ragione concreta

- **Spring WebFlux come modello di programmazione del server** — nessun
  beneficio reale per una webapp a navigazione prevalentemente
  server-rendered; aggiunge solo complessità (Mono/Flux nei controller).
  Restare su Spring MVC classico (`spring-boot-starter-webmvc`). Eccezione
  isolata e deliberata: lo starter Spring AI (vedi Stack sotto) porta
  Reactor/WebFlux in classpath per il *client* HTTP verso i provider LLM,
  non per servire richieste — non è un'apertura generale a WebFlux nel
  resto del progetto.
- **React/Vue/Angular come framework applicativo** — duplicherebbe la
  gestione di routing/stato che il server già fa. Se serve un widget
  isolato, montarlo come Web Component su un `<div>` mirato, non riscrivere
  la navigazione.
- **Tailwind come sistema di stile del resto del sito** — resta scoped ai
  componenti Pines UI copiati in pagina (vedi Stack sotto): non riscrivere
  markup/CSS esistente con utility Tailwind solo perché e' disponibile,
  `theme.css` con le custom properties resta la fonte di verità per tutto
  il resto.
- **Un build step Tailwind (CLI/PostCSS)** — il Play CDN (JIT nel
  browser) basta per l'uso scoped ai componenti Pines; niente
  `tailwind.config.js`/pipeline di build finché non serve qualcosa che il
  Play CDN non copre.

## Stack

| Livello | Scelta | Perché |
|---|---|---|
| Backend | Spring Boot 4.x, Spring MVC | Coerente con lo stack Spring esistente, nessun context-switch |
| Template engine | Thymeleaf | Fragment nativi, integrazione naturale con Spring MVC |
| Layout manager | thymeleaf-layout-dialect | `layout:decorate`/`layout:fragment` al posto di fragment parametrizzati scritti a mano: la BOM di Spring Boot ne gestisce la versione, nessuna dipendenza aggiuntiva da tracciare |
| Navigazione parziale | htmx (via CDN) | Markup dichiarativo via attributi, niente build |
| Micro-interattività | Alpine.js (via CDN) | Stato dichiarato inline, niente build |
| Componenti UI pronti (scoped) | Pines UI (devdojo.com/pines) + Tailwind Play CDN | Componenti Alpine.js gia' scritti (dropdown, modali, tabs...) da copiare cosi' come sono; usano classi Tailwind, per questo Tailwind e' caricato via Play CDN (JIT nel browser, zero build) con `corePlugins.preflight: false` per non toccare lo stile di base del resto del sito |
| Theming | CSS Custom Properties | Cambio tema = cambio attributo `data-theme`, zero ricalcolo server |
| Persistenza | Spring Data JPA + H2 file-based | Metadata delle generazioni (prompt/parametri/stato/file immagine); DB embedded su file locale, zero server esterno |
| Migrazioni schema DB | Flyway (`spring-boot-starter-flyway`) | Lo schema e' versionato in SQL esplicito, non dedotto da Hibernate (`ddl-auto: validate`): ogni modifica al DB e' una migrazione tracciabile, riproducibile, mai un'alterazione implicita a runtime |
| Client HTTP verso Replicate | `RestClient` (`spring-boot-starter-restclient`) | Sincrono, nessuna dipendenza WebFlux/reactor per questo client |
| Assistente chat (OpenRouter) | Spring AI (`spring-ai-starter-model-openai`) via `ChatClient`, `base-url` puntato su `https://openrouter.ai/api/v1` | OpenRouter espone un'API OpenAI-compatibile: nessun client HTTP custom da scrivere/mantenere. Richiede Spring Boot 4.x (Spring AI 2.0.x) — vedi eccezione WebFlux sopra |
| Build | Maven | — |
| Java | 21 | LTS |

Il package radice del codice applicativo è `org.dual.replicate`.

## Struttura del progetto

```
src/main/java/org/dual/replicate/
  Application.java              # entry point Spring Boot
  controller/
    HomeController.java         # pagina intera, esempio minimo
    GenerationController.java   # crea una generazione + polling htmx dello stato
    GalleryController.java      # galleria (load more) + dettaglio singola immagine
    DeepChatController.java     # pagina che ospita il Web Component <deep-chat>
    DeepChatApiController.java  # endpoint JSON per <deep-chat> (non fragment HTML)
  domain/
    Generation.java             # entity JPA: prompt, modello, parametri, stato, file immagine
    GenerationStatus.java
    ChatMessage.java            # entity JPA: un turno persistito di /deep-chat (ruolo, testo, immagine opzionale)
    ChatMessageRole.java
  repository/
    GenerationRepository.java
    ChatMessageRepository.java
  replicate/
    ReplicateClient.java        # wrapper RestClient sulle API Replicate
    PredictionResponse.java
    ReplicateException.java
    CollectionResponse.java     # risposta di GET /collections/{slug}
    ReplicateModelSummary.java  # owner/name/description di un modello (collection o singolo)
    ReplicateModelCatalog.java  # precarica all'avvio i modelli per la dropdown di /deep-chat
  search/
    SearxngClient.java          # wrapper RestClient su un'istanza SearXNG (Basic Auth)
    SearxngResponse.java, SearchResult.java, SearxngException.java
  service/
    GenerationService.java      # crea la prediction, fa avanzare lo stato, orchestra il download
    ImageStorageService.java    # scrive i file immagine su storage.images-dir
    DeepChatService.java        # orchestrazione del Web Component <deep-chat>, persiste la cronologia
    WebSearchTool.java          # tool Spring AI: ricerca web via SearxngClient
    ImageGenerationTool.java    # tool Spring AI: genera un'immagine via GenerationService
    GenerationResultHolder.java # canale d'uscita tool->DeepChatService (via ToolContext)
  config/
    StorageConfig.java          # espone storage.images-dir come /images/**

src/main/resources/
  application.yml
  db/migration/
    V1__create_initial_schema.sql   # schema Flyway, vedi sezione dedicata sotto
    V2__drop_chat_tables.sql         # rimossa la persistenza della vecchia chat di rifinitura prompt
    V3__create_chat_message.sql      # persistenza della cronologia di /deep-chat (vedi Scopo, punto 3)
  templates/
    index.html                   # home
    generate.html                 # form nuova generazione
    generation-status.html       # pagina di stato/polling di una generazione
    gallery.html                 # galleria (load more)
    gallery-detail.html          # dettaglio di una generazione
    deep-chat.html                # pagina che ospita <deep-chat> + combobox modello
    fragments/
      layout.html                # shell HTML condivisa (head, footer), decoratore layout-dialect
      header.html                # header di navigazione + theme switch, incluso da layout.html
      generate-form.html         # fragment del form (riusato anche per mostrare errori)
      generation.html            # fragment di stato di una generazione (polling)
      gallery.html               # fragment card + load more della galleria
  static/
    css/theme.css                # tutti i design token e gli stili
```

Le immagini generate e il DB H2 vivono in `./data/` (fuori da git, vedi
`.gitignore`), non sotto `static/`: sono stato applicativo prodotto a
runtime, non asset del progetto.

## Pattern Thymeleaf: layout manager (thymeleaf-layout-dialect)

`fragments/layout.html` e' un template decoratore: ogni pagina lo applica
con `layout:decorate` sul proprio `<html>` e marca il blocco da inserire
con `layout:fragment="content"`:

```html
<!DOCTYPE html>
<html lang="it" xmlns:th="http://www.thymeleaf.org" xmlns:layout="http://www.ultraq.net.nz/thymeleaf/layout"
      layout:decorate="~{fragments/layout}">
<head>
    <title>Titolo pagina</title>
</head>
<body>
<div layout:fragment="content">
    <!-- contenuto -->
</div>
</body>
</html>
```

Comportamento di default del dialect, da tenere a mente:

- il `<title>` della pagina **sostituisce** quello di `layout.html`
  automaticamente — non serve marcarlo con `layout:fragment`;
- il resto di `<head>` viene **fuso** (unione, non sostituzione): elementi
  aggiuntivi in `<head>` nella pagina (es. un `<meta>`/`<noscript>` come in
  `generation-status.html`) finiscono nell'head finale insieme a quelli di
  `layout.html`, senza doverli dichiarare come fragment;
- un fragment della pagina **sostituisce l'elemento del decoratore tag
  incluso**, non solo il suo contenuto — per questo il fragment `content`
  nelle pagine e' un `<div>` come nel decoratore, non un `<main>`: il
  `<main class="container">` unico che li contiene entrambi
  (`content` e il fragment opzionale `breadcrumbs`, vuoto se la pagina non
  lo definisce) vive solo in `layout.html`. Ripetere `<main>` o la classe
  `container` nella pagina produrrebbe un `<main>` annidato (HTML non
  valido) e il padding raddoppiato.

Per creare una nuova pagina: copiare questo scheletro, non serve toccare
`layout.html`. Attenzione: il fragment `content` della pagina resta un
semplice `<div>`, **senza** `class="container"` — quella classe vive solo
sul `<main class="container">` di `layout.html` (vedi punto sopra):
aggiungerla anche nella pagina non avrebbe alcun effetto sul markup
finale (`<main>` non annidabile, quel `<div>` non lo sostituisce) ma
confonderebbe chi legge il template sull'origine del margine laterale.

## Pattern controller: quando restituire fragment vs pagina intera

Regola: **stessa URL, due risposte**, distinguendo in base all'header
`HX-Request` che htmx aggiunge automaticamente a ogni sua richiesta.

```java
@GetMapping("/{id}")
public String status(@PathVariable Long id,
                      @RequestHeader(value = "HX-Request", required = false) String hxRequest,
                      Model model) {
    // ... popolare il model ...
    boolean isHtmxRequest = "true".equalsIgnoreCase(hxRequest);
    return isHtmxRequest ? "fragments/generation :: status" : "generation-status";
}
```

Attenzione se il fragment prende parametri (es. la paginazione della
galleria sotto): restituito come **vista di risposta diretta** da un
controller, Thymeleaf richiede parametri **nominati**, non posizionali
— `frag(nome=${valore})`, non `frag(${valore})`. La forma posizionale
funziona solo dentro un `th:replace` inline in un altro template (dove
la espressione la valuta il parser OGNL/SpringEL, non
`ThymeleafView.renderFragment`), altrimenti va in 500 con
`IllegalArgumentException: Parameters in a view specification must be
named`. Vedi `GalleryController` per l'uso corretto.

Vantaggi di questo pattern rispetto ad avere due endpoint separati:

- un solo URL, condivisibile/bookmarkabile, che funziona sia con
  JavaScript disabilitato (fallback a navigazione piena) sia con htmx;
- nessuna duplicazione di markup: il fragment dei risultati è lo stesso
  sia che venga incorporato nella pagina intera sia che venga restituito
  da solo.

Esempi già implementati da copiare:

- `GenerationController` — polling di stato, fragment senza parametri.
- `GalleryController` — paginazione numerata, i link sostituiscono
  l'intero contenuto con `hx-target="#gallery-content"` +
  `hx-swap="innerHTML"` (vedi `fragments/gallery.html`); il fragment
  prende parametri nominati, e' l'esempio da seguire per quel caso.

## Convenzione: attributi che portano un URL dell'app

Ogni attributo che porta un URL dell'app — `href`, `src`, `action`,
`hx-get`/`hx-post`/`hx-put`/`hx-delete`, o un URL passato a un Web
Component (es. `connect` di `<deep-chat>`, vedi `deep-chat.html`) —
passa **sempre** da `@{...}`, anche quando il path e' letterale e non
dipende dal model. L'app puo' girare dietro un reverse proxy su un
subpath (vedi `server.forward-headers-strategy` in `application.yml`):
solo `@{...}` tiene conto del prefisso (`X-Forwarded-Prefix`) a
runtime — una stringa scritta a mano come `"/generations"` lo ignora
sempre, "statica" o no. Non e' teorico: e' un bug reale che si e'
presentato appena l'app e' stata montata sotto un subpath (vedi il fix
su `fragments/generate-form.html` e `deep-chat.html`).

Per un attributo `hx-*` standard basta il prefisso `th:` — Thymeleaf lo
riconosce come "generic attribute setter" anche per attributi non
standard come `hx-get` — con `@{...}` dentro, path letterale o con
parametri dal model:

```html
<form th:hx-post="@{/generations}" hx-target="#generation-panel" hx-swap="innerHTML"
      th:action="@{/generations}" method="post">
```

```html
<a th:hx-get="@{/gallery(page=${p})}" hx-target="#gallery-content" hx-swap="innerHTML">...</a>
```

Fuori da un attributo htmx (es. `connect` di `<deep-chat>`, letto dal
componente via JavaScript, non da htmx: non esiste un `th:connect`) non
c'e' un prefisso `th:` diretto da applicare: serve `th:attr` con
`@{...}` interpolato dentro una literal substitution Thymeleaf
(`|...|`), vedi `deep-chat.html`.

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

## Convenzione: migrazioni database (Flyway)

Lo schema del database **non** e' gestito da Hibernate: `spring.jpa.hibernate.ddl-auto`
e' `validate`, non `update`/`create`. Hibernate all'avvio controlla solo che
le tabelle create da Flyway corrispondano alle entity JPA — se non
corrispondono l'app non parte (fail-fast, niente drift silenzioso tra
codice e schema).

Ogni modifica alla persistenza (nuova entity, nuovo campo, nuovo indice,
rename di colonna...) va fatta con una **nuova migrazione SQL** in
`src/main/resources/db/migration/`, mai lasciando che sia
`ddl-auto: update` a dedurla da solo:

1. Aggiungere/modificare l'entity JPA come al solito.
2. Creare `V<N+1>__<descrizione>.sql` (numero progressivo, mai riusare
   uno gia' applicato — Flyway calcola un checksum di ogni file e fallisce
   l'avvio se un file gia' eseguito viene modificato) con il DDL H2
   corrispondente (`CREATE TABLE`, `ALTER TABLE ADD COLUMN`, ecc.).
3. Avviare l'app: Flyway applica automaticamente le migrazioni non ancora
   eseguite (traccia lo stato in `flyway_schema_history`) prima che
   Hibernate validi lo schema.

In sviluppo, se serve ripartire da zero (schema o dati inconsistenti),
si puo' cancellare l'intera `./data/db/` (e' stato locale, non versionato,
vedi sopra): Flyway ricrea tutto dalle migrazioni al prossimo avvio.

## Convenzione: internazionalizzazione (i18n)

Tutto il testo utente-visibile (template e messaggi d'errore Java) passa
dal meccanismo nativo di Spring/Thymeleaf (`MessageSource` + `#{...}`),
non da stringhe hardcoded: la lingua cambia **automaticamente** in base
all'header `Accept-Language` del browser, nessuno switcher manuale,
nessuna persistenza in cookie/sessione. Il `LocaleResolver` e'
`AcceptHeaderLocaleResolver`, gia' il default di Spring Boot MVC — nessun
bean `LocaleResolver`/`WebMvcConfigurer` da scrivere, basta
`spring.web.locale`/`spring.messages.*` in `application.yml`.

Due bundle in `src/main/resources/`: `messages.properties` (italiano,
default/fallback — usato anche per qualunque locale browser non mappata,
es. "de") e `messages_en.properties` (inglese). Per aggiungere una
lingua: nuovo `messages_<locale>.properties` con le stesse chiavi (un
test, `TemplateRenderingTests.messageBundlesHaveMatchingKeys`, verifica
che i bundle abbiano lo stesso keyset — aggiornarlo per confrontare
anche il nuovo file). Convenzione chiavi: punto-separate,
`<pagina-o-componente>.<categoria>.<elemento>` (es. `header.nav.home`,
`galleryDetail.title`, `replicate.error.tokenMissing`) — il namespace e'
solo convenzionale, il bundle resta un unico file piatto sia per le
stringhe di template sia per i messaggi d'errore Java.

**Regola apostrofi (non ovvia, causa bug silenziosi)**: Spring passa un
messaggio per `java.text.MessageFormat` **solo** quando la chiamata ha
argomenti non nulli. `#{key}`/`Messages.get(code)` senza parametri →
niente `MessageFormat` → apostrofi letterali restano non-escaped
(`l'app`, `puo'`); `#{key(${arg})}`/`Messages.get(code, args...)` con
parametri → **sempre** `MessageFormat` → un apostrofo letterale va
raddoppiato (`''`) o scompare silenziosamente. Stessa attenzione per un
argomento numerico (id, durata): passa per `NumberFormat` e inserisce
separatori di migliaia locale-dependent — usare il sotto-pattern
`{0,number,#}`, non un bare `{0}`.

Lato Java, `org.dual.replicate.i18n.Messages` (wrapper su
`MessageSourceAccessor`, che risolve implicitamente sulla locale della
richiesta corrente via `LocaleContextHolder`) va iniettato ovunque un
messaggio d'errore possa arrivare all'utente — non solo nei controller:
oggi in `ReplicateClient`, `SearxngClient`, `GenerationService`,
`ImageStorageService`, `GalleryController`, `DeepChatApiController`,
`DeepChatService`. La risoluzione avviene sempre al call site, prima di
costruire l'eccezione (`throw new ReplicateException(messages.get(...))`),
mai nel costruttore dell'eccezione. Attenzione ai nomi: se la classe usa
gia' `messages` come variabile locale per qualcos'altro (es.
`DeepChatService`, dove `messages` e' la lista di turni della
conversazione), chiamare il campo iniettato diversamente (li' e'
`i18n`) per evitare lo shadowing.

Un binding Alpine (`:title`, `:placeholder`, ecc.) non passa mai da
Thymeleaf server-side: `#{...}` su quell'attributo non avrebbe alcun
effetto. Si ponte con `data-*` attributi renderizzati dal server, letti
a runtime via `$el.dataset` (vedi il toggle del pannello impostazioni in
`deep-chat.html`) — pattern riusabile per qualunque altra stringa
client-only, non il pattern `th:inline="javascript"` gia' in uso altrove
in quel file per i payload JSON.

`<html lang="...">` e' risolto dal bundle stesso
(`html.lang=it`/`en` nelle properties, `th:lang="#{html.lang}"` sul
decoratore `fragments/layout.html`), **non** da `#{#locale.language}`:
su una locale non mappata il contenuto servito ricade comunque
sull'italiano, quindi `lang` deve seguire il bundle effettivamente
usato, non la locale richiesta, altrimenti mentirebbe sulla lingua reale
del testo. Le pagine (`index.html`, `generate.html`, ecc.) non devono
avere un `lang` letterale sul proprio `<html>`: per il comportamento di
merge di thymeleaf-layout-dialect (vedi sezione layout manager sopra),
un attributo letterale presente su entrambi i lati vince da quello della
pagina, vanificando il `th:lang` dinamico del decoratore.

Limite noto e accettato: `Generation.errorMessage` (persistito su DB
quando una generazione fallisce) viene risolto nella locale della
richiesta che ha generato l'errore e salvato gia' tradotto — se la
lingua del browser cambia dopo, il testo persistito resta congelato
nella lingua di scrittura (nessuna migrazione per salvare chiave+
parametri invece del testo risolto). Allo stesso modo il catch-all di
`DeepChatApiController` non puo' tradurre messaggi di eccezioni
arbitrarie di framework terzi (Spring AI, errori di rete): solo il
prefisso `"Errore nel contattare l'assistente: "` e' tradotto, il resto
resta quello che la libreria ha restituito.

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
   con il pattern layout manager sopra. Basta.
2. Serve un aggiornamento parziale (ricerca live, paginazione, form senza
   reload)? → estrarre la porzione riusabile in `fragments/<nome>.html`,
   far restituire al controller quel fragment quando `HX-Request` è
   presente, la pagina intera altrimenti (vedi `GalleryController`).
3. Serve solo interattività locale, nessuna chiamata al server (aprire/
   chiudere un pannello, validare un campo)? → `x-data`/`x-show`/`x-on`
   di Alpine.js, direttamente nel template, senza controller dedicato.
4. Serve davvero un componente complesso stateful (editor, canvas,
   grafico)? → valutare un Web Component isolato prima di introdurre un
   framework SPA per l'intera app.
5. La feature tocca un'entity JPA (nuovo campo, nuova tabella, nuova
   relazione)? → nuova migrazione Flyway in `db/migration/` (vedi
   sezione dedicata sopra), mai affidarsi a `ddl-auto` per crearla.
6. Introduce testo letterale utente-visibile (label, messaggio
   d'errore)? → chiave in entrambi i bundle (`messages.properties`,
   `messages_en.properties`, vedi sezione i18n sopra), mai una stringa
   hardcoded in un template o nel costruttore di un'eccezione.
