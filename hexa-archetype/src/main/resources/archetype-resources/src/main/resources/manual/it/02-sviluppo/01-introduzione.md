# Introduzione per lo sviluppatore

Guida a hexa-core e hexa-ai, le librerie su cui è costruita questa applicazione. Questa guida è un normale gruppo del manuale online: si può modificare, ampliare o cancellare, e mostra come si scrive un manuale (vedi [Il manuale online](09-manuale-online.md)).

## Cosa sono le librerie

**hexa-core** fornisce l'infrastruttura di una webapp hypermedia-first (Spring MVC, Thymeleaf, htmx, Alpine): layout e componenti, registro degli eventi di sistema, token API cifrati, storage dei binari, backup, push SSE e manuale online. **hexa-ai** si aggiunge sopra (chat con strumenti, ricerca semantica su pgvector, servizi AI, crediti) e compare solo se l'app è stata generata con `-DuseAi=true`. hexa-core non dipende mai da hexa-ai.

## Come si usano

L'app è un host: dipende dai jar tramite `hexa-bom` (le versioni sono allineate e si cambiano con la proprietà `hexa.version` del `pom.xml`). Un'autoconfigurazione registra i sottosistemi delle librerie, quindi l'app può avere qualsiasi package radice e non scrive scansioni né `@EntityScan`. L'app si innesta nelle librerie in tre modi: implementando le SPI (interfacce in `port.in` che l'host realizza), riempiendo gli slot dei template, e fornendo configurazione e bundle di messaggi.

## Mappa della guida

- [Architettura esagonale](02-architettura-esagonale.md): come si organizza il codice.
- [Configurazione](03-configurazione.md) e [Persistenza e migrazioni](04-persistenza-e-migrazioni.md).
- [Web, htmx e Thymeleaf](05-web-htmx-thymeleaf.md) e il [Catalogo dei fragment](14-catalogo-fragment.md).
- [Eventi, errori e servizi remoti](06-eventi-errori-remoti.md), [Storage, token e backup](07-storage-token-backup.md), [Push in tempo reale](08-push-sse.md).
- [Il manuale online](09-manuale-online.md).
- Con hexa-ai: [Chat e strumenti](10-ai-chat.md), [Ricerca e crediti](11-ai-ricerca-e-crediti.md).
- Con hexa-pwa: [App installabile](15-pwa.md). In ogni app: [Blocco con PIN](16-blocco-con-pin.md).
- [Testare](12-testare.md) e [Ricette](13-ricette.md).

## Riferimento API

La documentazione javadoc è su [indice generale delle API](https://sdurz75.github.io/deep-flux/apidocs/index.html). Il punto di partenza sono i package `port.in` di ogni sottosistema: contengono le capability da usare e le SPI da implementare. Il modello da copiare è la slice `example/` di questa app (si apre da [/example](/example)).
