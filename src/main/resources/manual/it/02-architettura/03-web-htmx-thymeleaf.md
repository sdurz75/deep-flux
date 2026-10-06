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
- Le **select** non sono mai nude: usano il componente Pines (`pinesSelect`) che nasconde la select nativa ma la mantiene come fonte di verità.
- Un valore dinamico per un binding Alpine non passa da Thymeleaf: si porta in un attributo `data-*` e si legge a runtime.

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
