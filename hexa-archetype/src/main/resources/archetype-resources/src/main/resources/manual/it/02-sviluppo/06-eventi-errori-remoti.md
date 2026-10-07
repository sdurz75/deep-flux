# Eventi, errori e servizi remoti

Ogni errore interno e ogni chiamata a un servizio esterno passa dal registro degli eventi di sistema; non si scrive mai un `catch` che ingoia o si limita al log.

## Registrare un evento

[`ISystemEvents`](https://sdurz75.github.io/deep-flux/apidocs/org/dual/hexa/core/events/port/in/ISystemEvents.html) espone `record(operation, throwable[, subject])` per gli errori e `warn(source, operation, subject, message)` per gli avvisi (messaggio già tradotto). `record` non lancia mai e consegna il toast alla prima occorrenza di una serie di eventi uguali (finestra di 5 minuti). Registra chi gestisce o ingoia l'eccezione (servizi in background, strumenti); se l'eccezione risale a un controller, registra il controller. Gli eventi si vedono in [/system/events](/system/events).

## Una sorgente propria

Ogni servizio ha una `EventSource`: un enum dell'app che la implementa (vedi `ExampleEventSource`) più la chiave `events.source.<X>` nei due bundle. Per far comparire un link dall'evento alla pagina dell'oggetto si implementa `IEventLinkResolver` (vedi `ExampleEventLinks`).

## Toast

I toast viaggiano nell'header `HX-Trigger` (helper `HtmxEvents#addToastHeader`). Non si offre un pulsante «Riprova» sul toast: rieseguire una POST potrebbe duplicare un'operazione a pagamento.

## Servizi remoti

Un nuovo servizio remoto richiede: un valore di sorgente e la sua etichetta nei bundle; `FooException extends RemoteServiceException`; un client che estende `RestRemoteClient` e implementa la `port.out`, con ogni chiamata dentro `remote.call(...)`. `RemoteServiceException` porta `source()` e `kind()`: `TRANSIENT` (ritentabile), `PERMANENT`, `CONFIGURATION`, `REJECTED` (esito atteso, non si registra). `RemoteCaller` ritenta solo i transienti: per le operazioni non idempotenti o a pagamento si passa `RetryPolicy.NONE`. I client usano il `RestClient.Builder` iniettato, mai `RestClient.create()`.

## Il flusso completo, già funzionante

La slice `example/` contiene un client vero, da copiare: `ExampleRemoteClient` (estende `RestRemoteClient`, ogni chiamata in `remote.call`), `ExampleRemoteException` (porta la sorgente `EXAMPLE` e il `Kind`), la porta `IExampleRemote` e il servizio `ExampleRemoteStatusService`. Il pulsante «Controlla stato» della pagina `/example` lo percorre fino in fondo:

1. `RemoteCaller` traduce ogni errore HTTP o di rete in un `Kind` e ritenta, con la policy della chiamata, **solo** i `TRANSIENT` (408, 429, 5xx, rete): un ritentativo riuscito non lascia tracce.
2. L'eccezione definitiva risale senza `catch` fino al resolver del core, che la registra **una volta** (`/system/events`, campanella, log) e risponde 502 per un guasto, 500 per un bug e 422 senza registro per un rifiuto atteso (`REJECTED`).
3. Per una richiesta htmx il resolver aggiunge anche il toast nell'header `HX-Trigger`: il target non viene sostituito, l'utente vede il messaggio già tradotto. Un `CONFIGURATION` (qui l'URL `EXAMPLE_REMOTE_BASE_URL` mancante, il caso di partenza) è notificato ma non ritentato.
4. Dove l'errore si ingoia (lavoro in background, watcher) non c'è resolver: si chiama `ISystemEvents#record` a mano, come fa `ExampleService`.

Le operazioni non idempotenti o a pagamento passano `RetryPolicy.NONE` esplicita. Il test `ExampleRemoteClientTest` mostra il contratto senza rete (`MockRestServiceServer`); `PagesRenderingTests` verifica 502 e toast. Per provarlo dal vero basta impostare `EXAMPLE_REMOTE_BASE_URL` nel `.env` verso un endpoint che risponda a `GET /status`.

## Riferimento API

[`ISystemEvents`](https://sdurz75.github.io/deep-flux/apidocs/org/dual/hexa/core/events/port/in/ISystemEvents.html), e in [`org.dual.hexa.core.kernel.remote`](https://sdurz75.github.io/deep-flux/apidocs/org/dual/hexa/core/kernel/remote/package-summary.html): `RemoteCaller`, `RetryPolicy`, `RemoteServiceException`.
