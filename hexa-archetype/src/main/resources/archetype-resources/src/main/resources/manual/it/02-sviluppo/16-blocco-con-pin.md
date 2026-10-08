# Blocco con PIN (hexa-core)

Ogni app costruita su hexa-core ha il blocco con PIN: nessuna dipendenza in più. Senza PIN impostato non cambia nulla; l'utente lo attiva da [Sicurezza](/security), nel menu «Gestione» (la voce la aggiunge `navSystemCore`).

## Come funziona

- **Cancello lato server**: con il PIN attivo e la sessione bloccata passano solo `/unlock`, `/lock/now`, `/js/`, `/css/`, i path delle librerie e quelli che aggiungete voi con `app.lock.exempt-paths` (elenco separato da virgole, relativi al context path, un `/` finale vale come prefisso). Tutto il resto, `/events` e `/images/**` compresi, risponde con un redirect a `/unlock?next=...` (navigazione) o con un 401 (htmx, fetch, immagini). Un blocco solo nel browser sarebbe cosmetico.
- **Sessione e inattività**: lo sblocco vive nella sessione HTTP. Conta l'ultimo input vero dell'utente, che il browser comunica con `POST /lock/touch`; le richieste automatiche (polling, SSE) non contano, altrimenti l'app non si bloccherebbe mai. Scaduto il timeout il server blocca da solo.
- **PIN**: 4-8 cifre, solo l'hash PBKDF2 (con sale) nella tabella `app_lock`; dal quarto errore i tentativi rallentano (5 s, poi si raddoppia fino a 15 minuti). Ogni modifica a un blocco attivo chiede il PIN attuale.
- **Recupero**: `app.lock.reset=true` (variabile `HX_LOCK_RESET`) all'avvio spegne il blocco senza PIN e lo registra negli eventi; va tolta dopo l'uso.

## Cosa fa e cosa no

Protegge da un dispositivo incustodito o da un altro browser. Non cifra nulla (i dati in `localStorage` restano in chiaro) e, senza [hexa-oauth2](17-oauth2.md) (e quindi senza Spring Security), è l'unico accesso all'app. Con hexa-oauth2 il PIN resta sopra l'accesso: prima si entra con il provider, poi, dopo un periodo di inattività, serve il PIN. Il backup include la tabella `app_lock`.

## Estenderlo dalla vostra app o da una libreria

- Un path che deve restare raggiungibile da bloccati (per esempio un webhook) si aggiunge con `app.lock.exempt-paths`; una libreria implementa la porta `ILockExemptPaths`.
- Una libreria opzionale che ha UI ingaggia il layout del core implementando `ILayoutContributor` (fragment per `<head>` e fine `<body>`, voci del menu «Gestione»): il core non la conosce, la raccoglie. Il blocco usa lo stesso meccanismo per lo script di inattività e per la voce «Sicurezza».

## Testare

`PagesRenderingTests` verifica che la pagina Sicurezza si apra senza PIN e che l'app resti libera. Gli scenari del cancello (redirect, 401, scadenza, `next` ostile) sono in `LockHostTest` di hexa-core.
