# Push in tempo reale

Il server può avvisare il browser di un cambiamento senza polling, con Server-Sent Events.

## Come funziona

Il browser apre `GET /events` (il fragment `fragments/core/live-events.html` è già nel layout, su ogni pagina: non includerlo di nuovo; una sola tab per browser tiene la connessione, con Web Locks, e ritrasmette alle altre con un `BroadcastChannel`). Il servizio emette un evento con [`IClientPush`](https://sdurz75.github.io/deep-flux/apidocs/org/dual/hexa/core/push/port/in/IClientPush.html)`#emit(eventName, data)`; la pagina lo ascolta con htmx (`hx-trigger="sse:<nome>"`) o con Alpine.

## Eventi propri

I nomi degli eventi dell'app vanno dichiarati in `app.push.client-events` di `application.yml`: gli altri non vengono inoltrati al client. Un evento è un nome breve (per esempio `example-update`); il corpo è ciò che la pagina ricarica, di norma un fragment.

## Quando usarlo

Per esiti che arrivano in ritardo (un'elaborazione in background che termina). Per un aggiornamento che parte da un'azione dell'utente basta la risposta htmx.

## Riferimento API

[`IClientPush`](https://sdurz75.github.io/deep-flux/apidocs/org/dual/hexa/core/push/port/in/IClientPush.html), [`IClientPushStream`](https://sdurz75.github.io/deep-flux/apidocs/org/dual/hexa/core/push/port/in/IClientPushStream.html).

## Toast di successo

Per avvisare l'utente di un esito positivo avvenuto in background (un'elaborazione finita) inietta `INotifications` (`core.events.port.in`) e chiama `success(key, messaggio, path)`: `key` evita il doppione, `messaggio` è già tradotto, `path` (facoltativo, senza context path) è il link «Apri». Il toast compare su qualunque pagina aperta, non entra nel registro eventi né nella campanella, e viaggia sull'evento SSE `notice`, che il core gestisce sempre (non va in `app.push.client-events`). Errori e avvisi restano di `ISystemEvents`.
