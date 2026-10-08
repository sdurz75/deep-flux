# Catalogo dei fragment di hexa-core

Tutti i fragment generici stanno in `templates/fragments/core/` e si compongono dalle pagine e dai fragment dell'app. Regole comuni: parametri **nominati** e tutti passati (gli inutilizzati a `null`), testi e URL già risolti dal chiamante (`#{...}`, `@{...}`), mai `@bean`, `new`, `T(...)` o mappe SpEL dentro un parametro (si calcola con `th:with`). Le firme qui sotto sono quelle reali; per l'uso nei dettagli si legge il sorgente del fragment, che è piccolo e commentato.

## Struttura della pagina

- `layout` (decorato con `layout:decorate="~{fragments/core/layout}"`): carica htmx, Alpine e Tailwind, il tema, il toast, l'overlay e le conferme. Gli slot sono `content` e `breadcrumbs`.
- `header :: header`, `header :: navMenu(label, items, inline)`, `header :: navLink(path, text)`, `header :: navSystemCore` (voci Eventi e Token), `header :: themeSwitch`.
- `sidebar :: sidebar` e `status-bar :: bar(sidebarNav)`: menu laterale e barra di stato in basso, riempiti dai punti di estensione `fragments/app/nav` e `fragments/app/status-extras`.
- `breadcrumbs :: trail(group, parentPath, parentText, current)`: su ogni pagina tranne la home.

## Feedback e stato

- `toast :: container`, `notification-bell :: container` e `notification-bell :: bell(count, hasError, items)`: toast ed eventi di sistema, già nel layout.
- `busy-overlay :: container`: l'overlay "operazione in corso" durante i non-GET htmx, regolato da `data-busy`, `data-busy-text`, `data-busy-delay`.
- `confirm-dialog :: container`: la finestra delle conferme, già nel layout; si usa solo con `hx-confirm` o `window.hexaConfirm(domanda)`.
- `alert :: error(text)`: messaggio d'errore inline, per esempio un rifiuto atteso di un form.
- `live-events :: connect`: la connessione SSE a `GET /events`, già nel layout.
- `system-events :: content(...)` e `secrets :: list(...)`, `secrets :: secretForm(...)`: i contenuti delle pagine `/system/events` e `/tokens`, del core; l'app non li richiama.

## Bottoni

Mai `<button>` a mano. In `button.html`: `primary(type, text, extraClass)`, `neutral(type, text, hxGet, hxPost, hxTarget, hxSwap, extraClass)`, `danger(type, text, hxPost, hxDelete, hxConfirm, hxSwap, hxTarget, extraClass)`, `dangerSelectable(text, hxPost, hxConfirm, extraClass)`. Per i dialog: `dialogOpen(text, hxGet, hxTarget, hxSwap, neutral, extraClass)`, `dialogClose(text, extraClass)`, `dialogConfirm(text, extraClass)`, `dialogCloseIcon(title)`. Del chrome: `navToggle`, `popoverToggle`, `menuToggle`, `themeToggle`, `toastClose`, `bell`, `videoPlay`, `videoMute`, `videoFullscreen`. Se nessuno calza se ne aggiunge uno in `fragments/app/`.

In `field-buttons.html`, le icone dei campi: `tagRemove(title)`, `clearInput(title)`, `resetNumber(title)`, `stepNumber(direction, title)`, `removeFile(title)`.

## Campi di form

- `clear-field :: wrap(content, clearable, title)`: ogni `<input>` testo, search, date o number si avvolge qui (`content=~{::#id}`, id univoco, `clearable=false` = nudo). Dopo una scrittura da codice si lancia `clear-field:sync` sull'input.
- `number-field :: field(id, name, value, defaultValue, min, max, step, extraClass, spinner, refreshEvent, resetTitle, stepDownTitle, stepUpTitle)`: campo numerico con reset al default e passi.
- `select :: ui`: wrapper `pinesSelect` per ogni `<select>`, che resta la fonte di verità; un cambio da codice si annuncia con `select.dispatchEvent(new Event('pines-select:sync'))`.
- `switch :: toggle(id, name, text, checked, model, title)`: i booleani di un form, mai una checkbox nuda.
- `tag-chips :: editor(editorId, tags, addUrl, removeUrl, idName, idValue, target, swap, suggestions, removeTitlePattern, addPlaceholder, addLabel)`: editor di etichette con aggiunta e rimozione via htmx; la normalizzazione dei valori resta sul server. Una sola `<datalist>` di suggerimenti per pagina.
- `image-dropzone :: field(inputId, formId, maxBytes, maxFiles, compact, prompt, hint, invalidType, tooLarge, tooMany, removeTitle)` e `image-dropzone :: script`: caricamento di immagini con trascinamento e anteprima; i controlli di tipo e dimensione veri restano sul server.

## Contenitori e navigazione locale

- `modal :: dialog(titleId, maxWidth, body=~{::#id})`: la pagina dichiara `dialogOpen` e usa i bottoni `dialogOpen` e `dialogClose`.
- `fullscreen-modal :: screen(titleId, body=~{::#id})`: schermata a tutta finestra che non si chiude (focus trap, pagina inerte, nessuna variabile Alpine da dichiarare); la usa la schermata di blocco con PIN.
- `tabs :: list(extraClass, body)` e `tabs :: tab(text, href, hxTarget, selected, compact)`: la scheda attiva la decide il server.
- `accordion :: panels(labels, bodies, storageKey)` e `accordion :: staticPanels(labels, bodies)`.
- `popover :: floating(align, extraClass, bodyClass, trap, body)` dentro un antenato `x-data="pinesPopover"`; `popover :: panel(label, labelId, title, body, align, extraClass, icon)` quando il pannello porta la propria etichetta; `popover :: script` per lo script.
- `slideover :: drawer(persistent, body)`: pannello laterale di navigazione (assume `navOpen` su un antenato).
- `pagination :: nav(basePath, hxTarget, currentPage, totalPages, hasPrevious, hasNext, pageNumbers, ariaLabel)`: i numeri vengono da `PaginationSupport`. Per elenchi lunghi si può preferire lo scroll infinito.
- `description-list :: term(text)`: coppie termine e valore nelle schede di dettaglio.
- `chip :: neutral(text, textClass)` e `chip :: link(href, text, textClass)`: etichette compatte.

## Media

- `lightbox :: overlay`: visualizzatore a schermo intero per le immagini.
- `video-player :: player(url, extraClass, play, mute, volume, seek, fullscreen)` e `video-player :: script`: lettore video con i bottoni del core.

## Manuale

`manual :: toc(groups, current)`: l'indice della pagina `/manual`, già usato dal core.

## Dove si dichiara cosa

Un componente nuovo e riusabile entra in `fragments/core` solo se è davvero generico (testi e URL come parametri); altrimenti è un fragment dell'app in `fragments/app/`. Per i Web Component e gli script lo schema è lo stesso dei fragment `script` qui sopra: lo script si include una volta sola nel layout o dopo il markup che lo usa. Vedi anche [Web, htmx e Thymeleaf](05-web-htmx-thymeleaf.md#componenti).
