# Testare

`mvn test` richiede Docker: i test con contesto Spring usano un container PostgreSQL di `hexa-test-support`, mai il database di sviluppo.

## Cosa c'è nell'app

- `ArchitectureTest`: le regole di layering (vedi [Architettura esagonale](02-architettura-esagonale.md)); si lancia da solo e non richiede Docker.
- `PagesRenderingTests`: le pagine rendono col layout, i fragment htmx non includono il layout.
- `ApplicationTests`: il contesto parte.
- `BackupBlobColumnsTest`: ogni colonna di binari è dichiarata a `IBlobReferences`.
- Un test di servizio con un doppio della porta `out` (`ExampleServiceTest`): i servizi si provano senza contesto.

## Regole pratiche

I test `@SpringBootTest` condividono il database: quelli che scrivono sono `@Transactional` o ripuliscono a mano. I bundle dell'app sono disgiunti da quelli delle librerie: un test di rendering verifica anche che i testi risolvano. Nei test non si chiamano mai servizi esterni veri: si sostituiscono i gateway con `@MockitoBean` o `MockRestServiceServer`.

## Riferimento API

Il package `hexa-test-support` (`HexaArchitectureRules`, container di test) non è nel javadoc generale: si legge nei sorgenti del modulo.
