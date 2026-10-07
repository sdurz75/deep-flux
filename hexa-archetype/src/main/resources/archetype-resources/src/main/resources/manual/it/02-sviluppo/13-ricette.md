# Ricette

Liste di controllo per le modifiche più frequenti.

## Una nuova pagina o feature

1. Disegnare prima le porte (tipi di dominio, mai tipi web o Spring Data), poi gli adapter; copiare la slice `example/`.
2. Controller in `adapter/in/web` che parla solo con porte `in`, template in `templates/app/` col layout del core.
3. Voce in `fragments/app/nav.html` e breadcrumbs.
4. Aggiornamento parziale: un fragment in `fragments/app/`, restituito se la richiesta è htmx.
5. Testi in entrambi i bundle; bottoni e select con i componenti.
6. `mvn test` verde, `ArchitectureTest` compreso.
7. Aggiornare il manuale se cambia ciò che l'utente vede o fa.

## Un'entity

Una nuova migrazione in `db/migration/app` (vedi [Persistenza e migrazioni](04-persistenza-e-migrazioni.md)). Se tiene dei file nello storage, dichiararli con `IBlobReferences`.

## Un servizio remoto

Vedi [Eventi, errori e servizi remoti](06-eventi-errori-remoti.md): sorgente di eventi, eccezione, client con `remote.call(...)`, registrazione dell'errore dove lo si gestisce.

## Uno strumento della chat o una fonte ricercabile

Con hexa-ai: [Chat e strumenti](10-ai-chat.md) e [Ricerca e crediti](11-ai-ricerca-e-crediti.md).

## Togliere l'esempio

Cancellare la slice `example/` con la sua migrazione, la pagina e il fragment, la voce di menu, le chiavi di bundle, i test e la pagina `01-esempio.md` del manuale. Questa guida si può tenere, modificare o cancellare insieme al gruppo `manual.group.sviluppo`.

## Riferimento API

[indice generale delle API](https://sdurz75.github.io/deep-flux/apidocs/index.html).
