# Web, htmx e Thymeleaf

Come si costruisce una pagina e quali convenzioni valgono nell'interfaccia.

## Layout e pagine

Ogni pagina si decora con `layout:decorate="~{fragments/core/layout}"` e mette il proprio contenuto in `<div layout:fragment="content">`. Il decoratore risolve la lingua dell'elemento `html` e il titolo; il resto di `head` viene fuso. L'unico `main` sta nel layout. Le pagine dell'app stanno in `templates/app/`, quelle del core in `templates/core/`; ogni pagina (tranne la Home) mostra le **breadcrumb**.

## Pagina intera o fragment

La **stessa URL** dà due risposte, distinte dall'header `HX-Request`: htmx riceve solo il frammento da sostituire, una visita normale la pagina intera. Un fragment con parametri richiesto come vista deve avere i **parametri nominati**.

Un controller (adapter in `web`) parla solo con le porte `in`: mai con repository, classi di application o porte out.

## Gli URL e il reverse proxy

Ogni attributo che porta un indirizzo dell'app (`href`, `src`, `action`, `hx-get`, `hx-post`...) passa da `@{...}`, anche se il percorso è letterale: l'app può stare dietro un reverse proxy su un sottopercorso, e solo `@{...}` applica il prefisso `X-Forwarded-Prefix`. Lato Java si usa `request.getContextPath() + "/gallery"`. Anche il manuale rispetta la regola: il convertitore antepone il prefisso ai link, vedi [Estendere e questo manuale](09-estendere-e-manuale.md).

## Stile e tema

Solo Tailwind, con la configurazione in un file unico, `src/main/tailwind/tailwind.config.js`: la legge il Play CDN e, nel profilo Maven `tailwind`, la CLI standalone che produce un CSS minificato (opzionale). Mai colori scritti a mano: i colori sono **token** (`canvas`, `surface`, `ink`, `line`, `accent`, `danger`, `warning`...), ognuno con una variante chiara e una scura.

- Il tema scuro si attiva dall'attributo `data-theme`, non dalla preferenza del sistema. Un piccolo script all'inizio di `head` lo risolve prima che la pagina si veda, per evitare il lampeggio.
- Nello strato base ci sono solo gli elementi nudi (link, `code`, controlli dei form). Tutto il resto è classi inline: niente nuove regole `@layer`.
- I **bottoni** non si scrivono a mano: si usano i fragment di `fragments/core/button.html` e `fragments/core/field-buttons.html` (generici) e `fragments/app/button-gen.html` (azioni del dominio). I fragment sono divisi in due sottoalberi: `core` (generico) e `app` (dominio); `core` non dipende da `app`, verificato da `TemplateLayeringTest`.
- I **campi svuotabili** (`<input>` testo/search/date/number) passano da `fragments/core/clear-field.html :: wrap(content=~{::#id}, clearable, title)`: l'input resta del chiamante (id obbligatorio e univoco), `clearable=false` lo lascia nudo, mai un reset scritto a mano. Dopo una scrittura da codice del valore si lancia `clear-field:sync` sull'input (oppure `generation-settings:restored` dal restore dei form). `ClearFieldFragmentTest`.
- Le **select** non sono mai nude: usano il componente Pines (`pinesSelect`) che nasconde la select nativa ma la mantiene come fonte di verità.
- Per un componente di interfaccia si parte da quelli di Pines UI elencati sotto, in [Componenti Pines supportati](#componenti-pines-supportati).
- Un valore dinamico per un binding Alpine non passa da Thymeleaf: si porta in un attributo `data-*` e si legge a runtime.

## Componenti Pines supportati

Pines UI (devdojo.com/pines) è una raccolta di componenti Alpine e Tailwind, usata senza build. Un componente Pines entra nel progetto come **fragment di `fragments/core`**, con i colori ridotti ai token del tema, i testi già risolti dal chiamante e lo script Alpine registrato una volta in `layout.html` (evento `alpine:init`, prima del core Alpine). Questo è l'elenco di quelli oggi supportati: chi ne aggiunge o ne toglie uno aggiorna la tabella.

| Componente Pines | Dove | Note |
|---|---|---|
| Select | `fragments/core/select.html` (`pinesSelect`) | obbligatorio per ogni selezione; la select nativa resta la fonte di verità |
| Accordion | `fragments/core/accordion.html` | pannelli a indice, apertura ricordata con `storageKey` |
| Popover | `fragments/core/popover.html` (`pinesPopover`) | `panel(label, labelId, title, body, align, extraClass)` (bottone + pannello) e `floating(align, extraClass, bodyClass, trap, body)` (solo il pannello, per chi ha un bottone proprio con `x-ref="button" @click="toggle()"`: campanella degli eventi e chip del credito); il contenuto resta nel DOM da chiuso. In Deep Chat ospita le impostazioni di generazione e la galleria della conversazione (slot `settings` dell'host, ancorati a destra); l'elenco delle conversazioni sta nella colonna collassabile (riga qui sotto) |
| Video | `fragments/core/video-player.html` (`pinesVideo`) | controlli propri al posto di quelli nativi; senza JavaScript resta il `<video>` nativo |
| Image gallery | `fragments/core/lightbox.html` | lightbox a schermo intero, usa il plugin Alpine `focus` |
| Pagination | `fragments/core/pagination.html` | stile Pines a tutta larghezza con riepilogo "Da X a Y di N", paginazione server |
| Modal (dialog) | `fragments/core/modal.html` (`dialog(titleId, maxWidth, body)`) + `dialogOpen`/`dialogClose` in `fragments/core/button.html` | scrim, focus trap e transizioni stanno solo nel fragment; il chiamante dichiara `dialogOpen` e passa il corpo come `~{::#id}`. Usato da `/tokens`, `/loras`, note di `/search`, selettore d'archivio, maschera e ritaglio |
| Full-screen modal | `fragments/core/fullscreen-modal.html` (`screen(titleId, body)`) | schermata che copre tutta la finestra e non si chiude (niente scrim né Esc); autosufficiente (dichiara da sé `x-data`), focus trap con pagina inerte e scroll bloccato, funziona anche senza JavaScript. Il corpo è del chiamante (`~{::#id}`) e decide la propria larghezza. Usato dalla schermata di blocco con PIN (`core/unlock.html`). Per un dialog che si apre e si chiude resta `modal :: dialog` |
| Conferma (modal) | `fragments/core/confirm-dialog.html` (incluso UNA volta da `layout.html`) | `hx-confirm="<domanda tradotta>"` apre il modal al posto del `confirm()` del browser (ascolta `htmx:confirm` e fa ripartire la richiesta su "Conferma"); da JavaScript `window.hexaConfirm(domanda)` ritorna una `Promise<boolean>`. Mai `confirm()` nativo (`ConfirmDialogTest` lo verifica sui template). La conferma con parola digitata di "Elimina tutto" resta un `modal :: dialog` a parte |
| Tabs | `fragments/core/tabs.html` (`list`, `tab`) | schede come link: la scheda attiva la decide il server (pagina intera o swap htmx con `hxTarget`), niente stato Alpine. Usato da `/gallery`, dal selettore d'archivio e da `/trainings` |
| Toggle (switch) | `fragments/core/switch.html` (`toggle`) | resta una `<input type=checkbox>` nativa (sr-only): invio del form e script di persistenza invariati; `model` = variabile Alpine per `x-model`. Per i booleani dei form, mai una checkbox nuda |
| Slideover | `fragments/core/slideover.html` (`drawer(persistent, body)`) | pannello laterale di navigazione, usato da `header.html` (solo sotto md, `persistent=false`) e `sidebar.html` (colonna fissa da md in su, `persistent=true`); assume `navOpen` su un antenato, da chiuso è `invisible` (fuori da tab order e screen reader), da aperto rende inerte la pagina sotto md |
| Colonna collassabile | `fragments/core/collapsible-column.html` (`collapsibleColumn`; `layout(storageKey, label, hideLabel, column, content)`) + `panelToggle` in `fragments/core/button.html` | colonna laterale a sinistra del contenuto, nascosta/mostrata dal bottone `panelToggle` (che il chiamante mette in `content`); da md in su in flusso con scelta ricordata in `localStorage` (`storageKey`), sotto md cassetto con scrim, focus trap ed Esc. Pines non ha un componente simile (il suo slide-over e' solo un overlay): e' l'estensione dell'idioma di `slideover`. Usato da Deep Chat per le conversazioni; la documentazione completa (parametri, contratto Alpine, avvertenze) e' nel commento del fragment |
| Copy to clipboard | `fragments/core/copy-to-clipboard.html` (`copy(text, label, copiedLabel, extraClass)`) | bottone che copia `text` negli appunti e mostra «Copiato» per 2 s; autosufficiente (`x-data` inline, nessuno script nel layout). Il testo viaggia in `data-copy`, le etichette arrivano già tradotte (`clipboard.copy`/`clipboard.copied`). `navigator.clipboard` in contesto sicuro, altrimenti `execCommand('copy')` su un `<textarea>` temporaneo. Usato per il prompt nel dettaglio di una generazione e per la descrizione di un'immagine importata |
| Dropdown menu | `fragments/core/header.html` (`navMenu`) | menu a tendina del header: `pinesPopover` + `popover :: floating` nella barra, gruppo che si espande sul posto nello slideover (`inline=true`) |

## Fragment per dashboard

Per una pagina di sintesi (la home è la prima) il core offre cinque fragment generici, senza JavaScript né librerie di grafici: solo HTML e utility Tailwind coi token del tema. Come tutti i fragment del core ricevono testi e URL **già risolti** e non conoscono l'app; ciascuno documenta in testa al file parametri ed esempio d'uso.

| Fragment | Parametri | A cosa serve |
|---|---|---|
| `fragments/core/stat-tile.html` (`tile`) | `label, value, hint, href, tone` | un numero in evidenza; con `href` tutta la tessera è cliccabile (link stirato); `tone` = `default`, `warning` o `danger` colora solo il numero |
| `fragments/core/card.html` (`section`) | `title, body, href, linkText` | riquadro con titolo e link «Vedi tutto» facoltativo; `body` = `~{::#id}` |
| `fragments/core/bar-chart.html` (`columns`) | `labels, values, titles, ariaLabel, tone` | serie a colonne (altezze in percentuale del massimo, un valore 0 resta una linea sottile); `titles` sono i tooltip |
| `fragments/core/breakdown.html` (`bars`) | `labels, values, valueLabels, ariaLabel` | ripartizione in barre orizzontali, la più lunga è il massimo |
| `fragments/core/empty-state.html` (`message`) | `text` | messaggio per una lista o un grafico senza dati |

Le liste dei grafici si preparano nel controller (già nella lingua della richiesta, vedi `DashboardController`) e devono avere la stessa lunghezza; i valori di una serie sono tutti dello stesso tipo (il massimo si ricava ordinando una copia). Un fragment con parametri nominati e `th:if` va avvolto in un `th:block`: `th:replace` ha la precedenza su `th:if` sullo stesso elemento. I dati che richiedono una chiamata remota (il credito) non stanno nel render: si caricano con htmx da un contenitore `display: contents` (vedi `/dashboard/credits`).

I bottoni di un componente Pines non si scrivono a mano: stanno in `fragments/core/button.html`.

## Internazionalizzazione

Ogni testo visibile passa da `MessageSource` e `#{...}`, mai stringhe nel template. La lingua segue `Accept-Language`, senza selettore. Due bundle: `messages-core*.properties` per il core e `messages*.properties` per l'app, con **chiavi disgiunte**; l'italiano è la lingua di riserva. Due trappole:

- con **argomenti** gli apostrofi vanno raddoppiati, altrimenti spariscono; senza argomenti restano singoli;
- i numeri usati come identificativi si scrivono `{0,number,#}`, per non avere separatori di migliaia.

Lato Java si inietta `Messages` e si risolve nel punto in cui si lancia l'errore, prima di costruire l'eccezione.

## Interazione e stato

- Un'operazione htmx non-GET blocca l'intera interfaccia con un **overlay** «operazione in corso» finché non finisce, così un secondo clic non avvia una seconda operazione a pagamento. Si opta fuori con `data-busy="off"`.
- I **toast** arrivano via SSE o via header `HX-Trigger`, e sono guidati da un payload generico: nessun codice per servizio.
- Lo stato delle form di parametri sta in `localStorage` per `/generations/new` e **sul server, per conversazione,** per Deep Chat.
- Il canale **SSE** `GET /events` porta alle schede aperte gli eventi dell'app (`gallery-update`, `chat-message`) e quelli di sistema.

Per cosa succede dietro il pulsante **Genera**, vedi [Generazione e Replicate](04-generazione-replicate.md).
