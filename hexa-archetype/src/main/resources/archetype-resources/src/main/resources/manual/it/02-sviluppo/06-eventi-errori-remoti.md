# Eventi, errori e servizi remoti

Ogni errore interno e ogni chiamata a un servizio esterno passa dal registro degli eventi di sistema; non si scrive mai un `catch` che ingoia o si limita al log.

## Registrare un evento

[`ISystemEvents`](https://sdurz75.github.io/deep-flux/apidocs/org/hexa/core/events/port/in/ISystemEvents.html) espone `record(operation, throwable[, subject])` per gli errori e `warn(source, operation, subject, message)` per gli avvisi (messaggio già tradotto). `record` non lancia mai e consegna il toast alla prima occorrenza di una serie di eventi uguali (finestra di 5 minuti). Registra chi gestisce o ingoia l'eccezione (servizi in background, strumenti); se l'eccezione risale a un controller, registra il controller. Gli eventi si vedono in [/system/events](/system/events).

## Una sorgente propria

Ogni servizio ha una `EventSource`: un enum dell'app che la implementa (vedi `ExampleEventSource`) più la chiave `events.source.<X>` nei due bundle. Per far comparire un link dall'evento alla pagina dell'oggetto si implementa `IEventLinkResolver` (vedi `ExampleEventLinks`).

## Toast

I toast viaggiano nell'header `HX-Trigger` (helper `HtmxEvents#addToastHeader`). Non si offre un pulsante «Riprova» sul toast: rieseguire una POST potrebbe duplicare un'operazione a pagamento.

## Servizi remoti

Un nuovo servizio remoto richiede: un valore di sorgente e la sua etichetta nei bundle; `FooException extends RemoteServiceException`; un client che estende `RestRemoteClient` e implementa la `port.out`, con ogni chiamata dentro `remote.call(...)`. `RemoteServiceException` porta `source()` e `kind()`: `TRANSIENT` (ritentabile), `PERMANENT`, `CONFIGURATION`, `REJECTED` (esito atteso, non si registra). `RemoteCaller` ritenta solo i transienti: per le operazioni non idempotenti o a pagamento si passa `RetryPolicy.NONE`. I client usano il `RestClient.Builder` iniettato, mai `RestClient.create()`.

## Riferimento API

[`ISystemEvents`](https://sdurz75.github.io/deep-flux/apidocs/org/hexa/core/events/port/in/ISystemEvents.html), e in [`org.hexa.core.kernel.remote`](https://sdurz75.github.io/deep-flux/apidocs/org/hexa/core/kernel/remote/package-summary.html): `RemoteCaller`, `RetryPolicy`, `RemoteServiceException`.
