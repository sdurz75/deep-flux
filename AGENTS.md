# AGENTS.md

Le istruzioni per gli agenti di codice di questo repository stanno in [`CLAUDE.md`](./CLAUDE.md) (stack, vincoli, architettura esagonale, convenzioni, comandi di build e test): leggilo per intero prima di modificare il codice.

Altri riferimenti:

- Manuale di sviluppo: [`deep-flux/src/main/resources/manual/it/02-architettura/`](./deep-flux/src/main/resources/manual/it/02-architettura/01-panoramica.md)
- Manuale d'uso: [`deep-flux/src/main/resources/manual/it/01-uso/`](./deep-flux/src/main/resources/manual/it/01-uso/01-introduzione.md)
- Usare hexa come base per una nuova app: [`docs/TEMPLATE.md`](./docs/TEMPLATE.md)
- Indice delle API pubbliche (porte `port.in` e SPI, con link ai sorgenti): [`docs/API.md`](./docs/API.md)
- Indice leggibile da macchina: [`llms.txt`](./llms.txt)

Comandi essenziali: `mvn test` (richiede Docker); solo architettura, senza Docker:
`mvn test -pl deep-flux -am -Dtest=ArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false`. Non avviare l'applicazione e non chiamare servizi a pagamento (Replicate) senza permesso esplicito.
