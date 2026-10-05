# Panoramica

Questa parte del manuale è per chi sviluppa o amministra il sistema. Spiega come è fatto deep-flux e perché. Il riferimento di dettaglio, scritto per chi modifica il codice, è `CLAUDE.md` nella radice del repository; qui trovi la versione leggibile, con i rimandi.

## A cosa serve

L'applicazione fa tre cose, per una sola persona (non ci sono account):

1. **Generare immagini e video**, con un chatbot o da un form, su Replicate.
2. **Archiviarli e indicizzarli**: galleria, preferiti, tag, immagini importate, ricerca per significato.
3. **Conservare la storia delle conversazioni** con l'assistente.

Il manuale d'uso di queste funzioni è nella [parte Uso](../01-uso/01-introduzione.md).

## La filosofia: hypermedia first

Non è una single page application. Il **server è la fonte di verità e risponde con HTML**, non con JSON; il browser arricchisce.

- La navigazione è Spring MVC con Thymeleaf.
- Gli aggiornamenti parziali (paginazione, polling, form senza ricarica) sono htmx.
- La micro-interattività locale (una tendina, un dialog) è Alpine.js.
- Un componente davvero complesso diventa un Web Component isolato su un singolo elemento, mai un framework.
- Lo stile è Tailwind, usato inline in tutto il sito.
- **Nessuno step di build frontend**: htmx, Alpine e Tailwind arrivano da una CDN.

Cosa **non** si introduce senza un motivo concreto: Spring WebFlux come modello del server (si resta su Spring MVC; l'unica eccezione deliberata sono i tipi Reactor per il flusso SSE), React, Vue o Angular, un build step obbligatorio per Tailwind.

## Lo stack

| Parte | Scelta |
|---|---|
| Linguaggio e build | Java 21, Maven, un solo modulo |
| Server | Spring Boot 4, Spring MVC |
| Pagine | Thymeleaf con il layout dialect |
| Interazione | htmx, Alpine.js, Pines UI (componenti Alpine e Tailwind) |
| Dati | PostgreSQL con l'estensione pgvector, Spring Data JPA |
| Migrazioni | Flyway, con `ddl-auto: validate` |
| Servizi esterni | Replicate, OpenRouter via Spring AI, SearXNG |
| Ricerca semantica | embedding locali ONNX (multilingual-e5-small) e `PgVectorStore` |
| Test | JUnit, ArchUnit per far rispettare l'architettura, Testcontainers |

## Come è organizzato il codice

Il codice si divide in due blocchi:

- **core**: la parte generica e riusabile (layout, eventi di sistema, push SSE, token cifrati, storage dei binari, backup, questo manuale);
- **app**: la parte specifica di questa applicazione (generazione, galleria, chat, ricerca, crediti, addestramento di LoRA).

Ognuno è fatto di sottosistemi, ognuno un **esagono** (ports and adapters). L'app dipende dal core, mai il contrario. Il repository è pensato anche come **template** per una nuova webapp: si tiene il core e si sostituisce l'app. Continua con [Esagoni, core e app](02-esagoni-core-app.md).
