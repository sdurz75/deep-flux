# Chat e assistente

Deep Chat è diviso in `core.chat` (il motore) e `app.chat` (i collegamenti con le generazioni). Insieme: conversazioni, turni, l'assistente basato su un modello linguistico con i suoi strumenti, e il collegamento con le generazioni.

## Un turno

La pagina carica il Web Component `<deep-chat>`, che parla con un endpoint JSON (`DeepChatApiController`). Per ogni messaggio:

1. `ChatService` salva l'ultimo messaggio dell'utente.
2. Costruisce la **cronologia del modello lato server** con `ChatHistoryBuilder`: il client manda solo l'ultimo messaggio. La finestra tiene gli ultimi quaranta turni entro sessantamila caratteri, parte sempre da un turno dell'utente, esclude i turni d'errore e fonde i turni consecutivi dello stesso ruolo.
3. Chiede la risposta a `IAssistant`, e salva anche quella.
4. Avvia un watcher per ogni generazione partita nel turno.

L'esito di una generazione, quando arriva, è un turno a sé. Al modello arriva come una nota di sistema in inglese («Generation #12 finished…»), mai col testo tradotto mostrato all'utente: così il modello vede gli esiti e il contesto non cresce senza limiti.

## L'assistente

`SpringAiAssistant` usa il `ChatClient` di Spring AI su OpenRouter (con l'interfaccia compatibile con OpenAI). Non elenca gli strumenti: riceve la lista dei **toolkit** presenti (`IChatToolkit`, un'interfaccia piccola con la chiave della sezione di prompt) e li registra tutti. Un toolkit condizionale, come la ricerca nell'archivio, manca quando la sua proprietà lo spegne, e con lui la sua sezione.

Gli strumenti, ognuno una classe con metodi `@Tool` e un `@Order`:

| Strumento | Cosa fa |
|---|---|
| WebSearchTool | ricerca sul web (SearXNG); i risultati sono dichiarati non fidati |
| LibraryTool | sola lettura dell'app: modelli, LoRA, tag, dettaglio generazione, eventi |
| ManualTool | consulta questo manuale, solo la parte Uso |
| ArchiveSearchTool | ricerca per significato o per filtri nell'archivio |
| CurationTool | mutazioni leggere e idempotenti su richiesta: stella, tag, titolo della conversazione |
| ActionProposalTool | propone un'azione: depone un bottone, non esegue |
| NoteTool | salva una nota |
| CreditsTool | legge il credito e il costo della conversazione |
| VisionTool | guarda un'immagine con un modello di visione |
| ImageGenerationTool | avvia **una** generazione a pagamento |

`ImageGenerationTool` è l'unico a pagamento: usa **sempre** il modello scelto nell'interfaccia, ricevuto dal `ToolContext` e non come parametro che l'LLM possa cambiare, ritorna subito l'identificativo e ha un tetto per turno (tre). I ritorni degli strumenti sono testo per il modello, in inglese, e i messaggi d'errore gli dicono di non riprovare da solo e di avvisare l'utente. Un rifiuto atteso torna così com'è; un guasto vero viene registrato negli eventi di sistema.

## Il system prompt a sezioni

Il system prompt non è un blocco unico: è assemblato da sezioni in `prompts.properties`. Sempre presenti: `core`, `guidance` (rende l'assistente moderatamente proattivo nel far scoprire ciò che sa fare) e `appmap` (dove stanno le pagine). Poi la sezione di ogni toolkit presente, nell'ordine di `@Order`, e infine il contesto creativo e la guida ai prompt per immagini. Un test (`ChatPromptTest`) fissa un tetto di lunghezza, verifica che ogni sezione appartenga a un toolkit o sia sempre presente, che i testi sempre presenti non nominino strumenti condizionali e che ogni strumento citato esista.

Per questo il manuale non è nel prompt: sarebbe troppo lungo per ogni turno. L'assistente lo legge a richiesta con `searchManual` e `readManualPage`, citando i link alle pagine.

## Generazioni e chat

La chat conosce una generazione solo per identificativo; la generazione conosce la conversazione solo come numero (`conversationId`, senza chiave esterna). `ChatGenerationWatcher` interroga Replicate in background e `persistOutcome`, idempotente, scrive il turno di esito; `ChatPushNotifier` lo spinge alla scheda via SSE come evento `chat-message`. Al ricaricamento i segnaposto delle generazioni in corso tornano da `Generation.conversationId`.

## Azioni proposte

Il modello non cancella né rigenera nulla da solo. `ActionProposalTool` legge e deposita un'azione in un contenitore che viaggia fino al client, dove diventa un bottone. L'esecuzione passa dai normali endpoint (`POST /generations/{id}/cancel`, `/delete`...) dopo una conferma, oppure apre un form già compilato. Le azioni non sono persistite.

## Il form per conversazione

La configurazione del form (modello, parametri, LoRA) si salva con la conversazione, in una colonna di testo JSON che la chat non interpreta, e scegliere una conversazione la ripristina. Un valore nullo indica una conversazione precedente a questa funzione (il browser salva subito il suo stato locale sul server), `{}` i default del catalogo.

## Errori

Se il modello linguistico fallisce, `SpringAiAssistant` lancia `AssistantException`, con gli id delle generazioni già avviate (che avranno comunque il loro watcher), e `ChatService` scrive un turno d'errore, in rosso e mai rimandato al modello. Per la ricerca che sta dietro `ArchiveSearchTool` vedi [Archivio e ricerca](06-archivio-e-ricerca.md).
