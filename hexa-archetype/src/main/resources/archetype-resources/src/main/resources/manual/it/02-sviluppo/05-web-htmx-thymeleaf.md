# Web, htmx e Thymeleaf

L'interfaccia è hypermedia-first: il server risponde con HTML, htmx aggiorna parti di pagina, Alpine gestisce la micro-interattività. Nessuna build del frontend: htmx, Alpine e Tailwind arrivano da CDN (il CSS compilato è opzionale con `-Ptailwind`).

## Le pagine

Ogni pagina decora il layout del core con `layout:decorate="~{fragments/core/layout}"` sul proprio `<html>` e mette il contenuto in un `<div layout:fragment="content">`. Le pagine stanno in `templates/app/`, i fragment in `templates/fragments/app/`. Il `<title>` della pagina sostituisce quello del layout.

## Stessa URL, due risposte

Una richiesta htmx (header `HX-Request`) riceve il solo fragment, una normale la pagina intera. Un fragment con parametri restituito come vista deve avere parametri nominati.

## Breadcrumbs e menu

Ogni pagina tranne la home mostra il percorso: `fragments/core/breadcrumbs :: trail(group, parentPath, parentText, current)`, con tutti i parametri nominati. Le voci di menu si aggiungono in `fragments/app/nav.html`; `status-extras.html` è l'altro punto di estensione del layout.

## Componenti

I bottoni non si scrivono a mano: si usano i fragment di `fragments/core/button.html` (o se ne aggiunge uno). Le select passano dal wrapper `pinesSelect` di `fragments/core/select.html`. Gli `<input>` testo/search/date/number si avvolgono con `fragments/core/clear-field.html :: wrap(content=~{::#id}, clearable=true, title=null)` (id obbligatorio e univoco; `clearable=false` lo lascia nudo, mai un reset scritto a mano): compare una **x** quando il campo è valorizzato. Dopo una scrittura da codice del valore si lancia `clear-field:sync` sull'input. Il catalogo completo, con le firme di ogni fragment, è in [Catalogo dei fragment](14-catalogo-fragment.md). Tre scheletri non si riscrivono: il dialog modale è `modal :: dialog(titleId, maxWidth, body=~{::#id})` (la pagina dichiara `dialogOpen` e usa i bottoni `dialogOpen`/`dialogClose` di `button.html`), le schede sono `tabs :: list(extraClass, body)` + `tabs :: tab(text, href, hxTarget, selected, compact)` (link: la scheda attiva la decide il server) e i booleani di un form sono `switch :: toggle(id, name, text, checked, model, title)` (checkbox nativa, mai una nuda). Le conferme si scrivono con `hx-confirm="domanda"` (i bottoni `danger` lo prendono già come parametro): il core le mostra in un modal Pines, da JavaScript si usa `window.hexaConfirm(domanda)` (Promise di booleano), mai il `confirm()` del browser. Il pannello laterale di navigazione è `slideover :: drawer(persistent, body=~{::#id})` (assume `navOpen` su un antenato). Un pannello ancorato con un bottone proprio usa `popover :: floating` dentro un antenato `x-data="pinesPopover"`.

## URL e tema

Ogni attributo con un URL passa da `@{...}`, così l'app funziona dietro un reverse proxy su sottopercorso. Il tema usa solo utility Tailwind e i token del tema (`canvas`, `surface`, `ink`, `accent`...), mai colori scritti a mano.

## Testi

Ogni testo visibile viene da `#{...}` e dal bundle, in entrambe le lingue.

## Riferimento API

Gli helper lato Java sono in [`org.dual.hexa.core.web`](https://git.trenolab.com/a.duca/hexa/src/branch/main/hexa-core/src/main/java/org/dual/hexa/core/web): `HtmxEvents` (toast e `HX-Trigger`), `PaginationSupport`, `TailwindAssets`.
