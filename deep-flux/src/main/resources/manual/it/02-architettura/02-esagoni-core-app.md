# Esagoni, core e app

Ogni sottosistema è un **esagono** pragmatico (ports and adapters). Sta in `org.dual.hexa.core.<sottosistema>` se è generico, in `org.dual.hexa.app.<sottosistema>` se è specifico di questa applicazione.

## La struttura di un esagono

```
<core|app>/<sottosistema>/
  domain/             entity JPA, value object, enum, eccezioni, eventi, regole pure
  application/        i casi d'uso: implementano le porte in; usano solo port.out e altre port.in
  port/in/            interfacce I<Capacità>: l'API del sottosistema
  port/out/           interfacce I<Cosa> implementate dagli adapter out
  adapter/in/<tech>/  chi pilota il sottosistema: web, scheduling, async, ai
  adapter/out/<tech>/ ciò che il sottosistema usa: persistence, replicate, http, webdav, push, search
```

Le interfacce iniziano **sempre** con `I` (`IGenerations`); le implementazioni no (`GenerationService`). Una porta in `IGenerations` ha l'implementazione `GenerationService`; una porta out `IGenerationStore` ha `JpaGenerationStore`. I repository Spring Data sono visibili solo dentro l'adapter di persistenza.

## Le regole

- Il **domain** conosce solo il JDK, la persistenza e altro domain: niente web, niente Spring AI.
- L'**application** non usa gli adapter, né Spring Web, HTTP, AI, Data, né file e immagini direttamente: l'I/O sta dietro una porta.
- Le **porte** dipendono solo dal domain e da altre porte.
- Un adapter **in** non usa un adapter **out** e viceversa.
- **Fra sottosistemi** si dipende solo dalla `port.in` e dal `domain` dell'altro, mai dalla sua `application` o dai suoi adapter. Nessun ciclo.
- Il **core non conosce l'app**.
- Non esiste codice fuori da `core` e `app` (niente package per layer come `controller` o `service`).

Queste regole sono verificate da `ArchitectureTest` (ArchUnit) e da `SourceImportsTest`, che applica le stesse regole ai sorgenti, Javadoc compresi: per citare una classe di un altro strato in un commento si usa `{@code Nome}`, mai `{@link}`. Una violazione si corregge nel codice, non allentando la regola.

## I sottosistemi

| Sottosistema | Responsabilità |
|---|---|
| core.kernel | errori e retry remoti, messaggi i18n, cifratura a chunk, paginazione |
| core.web | il kit UI: eventi htmx, paginazione, badge di build |
| core.events | registro degli eventi di sistema, campanella, toast |
| core.push | SSE verso le schede aperte |
| core.secrets | cifratura dei segreti a riposo |
| core.secrets | segreti cifrati con tipo (token API, password, segreti dei moduli), scadenze, la pagina Segreti |
| core.storage | i binari: nome, validazione, filesystem o WebDAV, `/images/{file}` |
| core.backup | export e import del sistema completo |
| core.manual | questo manuale |
| ai.chat | il motore di Deep Chat: conversazioni, turni, assistente, ricerca web, note, storico lato server |
| ai.llm | miglioramento del prompt, descrizione e didascalia delle immagini con un modello di visione, esecutore delle chiamate al provider LLM |
| ai.search | ricerca semantica: indice, riconciliazione, note |
| app.generation | generazioni, immagini importate, catalogo modelli, LoRA, galleria, costo |
| app.chat | il lato app di Deep Chat: strumenti legati alle generazioni, esiti, azioni proposte, recupero, pagina |
| app.search | la pagina `/search` |
| app.training | addestramento LoRA: dataset, ritaglio, didascalie, training su Replicate, risultato |
| ai.credits | credito residuo: riga OpenRouter e aggregazione delle `ICreditSource` dell'host |
| app.credits | stima del saldo Replicate (`ICreditSource` dell'app) e inserimento del saldo |
| app.shared | dominio comune dell'app (tag, sorgenti degli eventi, home) |

## Chi dipende da chi, nell'app

`ai.llm` e `ai.search` sono foglie del core; `ai.chat` dipende da `ai.llm`, `ai.search` e `core.manual` e non conosce le generazioni (le SPI `IChatToolkit`, `IChatTurnContributor`, `IChatPageContributor`, `IChatOutcomeResolver` le implementa l'app). Nell'app `generation` dipende da `ai.llm` e `ai.search`; `app.chat` da `generation`, `credits` e `ai.chat`; `credits` da `generation`; `training` da `generation` (preset e catalogo dei modelli) e da `prompt` (le didascalie), e nessuno dipende da `training`. `generation` non conosce `chat` (solo un numero, `Generation.conversationId`), e `ai.search` non conosce né `generation` né `chat`: legge i loro dati tramite una interfaccia di estensione (`ISearchableSource`) che loro implementano.

## I punti di estensione del core

Un'implementazione dell'app può implementare solo un elenco chiuso di porte out del core: il risolutore dei link degli eventi (`IEventLinkResolver`) e il catalogo dei tipi di segreto (`ISecretTypeCatalog`). Ogni altra porta del core, per l'app, non esiste. Il menu e le breadcrumb sono un altro punto di estensione: il core include `fragments/app/nav.html` e l'app lo riscrive.

## Aggiungere un sottosistema

1. Decidere se è generico (core) o specifico (app).
2. Creare solo i package che servono, disegnando prima le porte (senza tipi web, HTTP, Spring Data o Spring AI), poi gli adapter.
3. Se tocca il database: una nuova migrazione Flyway nella location giusta; nessuna chiave esterna dal core verso l'app.
4. Se serve un dato di un altro sottosistema senza che lui ti conosca, definire una interfaccia di estensione nella tua `port.in` che lui implementa.
5. Aggiungere i testi nel bundle giusto, in entrambe le lingue.
6. Far passare `mvn test`: `ArchitectureTest` deve restare verde.

Il resto delle convenzioni, pagina per pagina, è in [Web, htmx e Thymeleaf](03-web-htmx-thymeleaf.md).
