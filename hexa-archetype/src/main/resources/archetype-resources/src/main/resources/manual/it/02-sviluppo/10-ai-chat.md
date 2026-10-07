# Chat e strumenti (hexa-ai)

Questa pagina riguarda le app generate con `-DuseAi=true` (dipendenza `hexa-ai`). La chat sta nel core, in `/deep-chat`; l'app la personalizza con SPI e uno slot di template, senza che la chat conosca il dominio dell'app.

## Cronologia lato server

Il client invia solo l'ultimo messaggio; la cronologia è ricostruita dal server. Gli esiti di operazioni dell'app arrivano al modello come note di sistema in inglese, tramite riferimenti opachi (`outcomeRef`).

## Le SPI

- [`IChatToolkit`](https://sdurz75.github.io/deep-flux/apidocs/org/dual/hexa/ai/chat/port/in/IChatToolkit.html): un insieme di strumenti `@Tool` con una sezione di prompt (`promptSection`) e un `@Order`; `beginTurn`/`endTurn` aprono e chiudono il turno.
- [`IChatTurnContributor`](https://sdurz75.github.io/deep-flux/apidocs/org/dual/hexa/ai/chat/port/in/IChatTurnContributor.html): aggiunge dati al contesto degli strumenti a partire dalle impostazioni del client.
- [`IChatPageContributor`](https://sdurz75.github.io/deep-flux/apidocs/org/dual/hexa/ai/chat/port/in/IChatPageContributor.html): attributi per la pagina di una conversazione.
- [`IChatOutcomeResolver`](https://sdurz75.github.io/deep-flux/apidocs/org/dual/hexa/ai/chat/port/in/IChatOutcomeResolver.html): trasforma i riferimenti in esiti da mostrare.

Le implementazioni si iniettano come `Optional<...>` nei servizi della chat.

## Aggiungere uno strumento

Una classe con metodi `@Tool` che implementa `IChatToolkit`. I valori restituiti sono in inglese, dicono di non riprovare e di avvisare l'utente; lo strumento cattura le proprie eccezioni (un rifiuto atteso lo restituisce com'è, un guasto passa da `ISystemEvents#record`). Per gli strumenti a pagamento si pone un tetto per turno. La sezione di prompt va in `prompts.properties`, e un test (`ChatPromptTest` nell'app di riferimento) può imporre il tetto di lunghezza del prompt.

## Lo slot della pagina

La pagina è `core/deep-chat.html`; l'app la completa con un unico template indicato da `app.chat.host-fragment`, con gli slot `intro`, `settings`, `below`, `scripts`, `conversationTags`, `knownTags`. Lo script dello slot `scripts` definisce `window.deepChatHost` prima del modulo del core.

## Link nella chat

I link che il bot può citare sono un elenco chiuso di percorsi (`app.chat.link-paths`).

## Riferimento API

[`org.dual.hexa.ai.chat.port.in`](https://sdurz75.github.io/deep-flux/apidocs/org/dual/hexa/ai/chat/port/in/package-summary.html), [`IPromptEnhancer`](https://sdurz75.github.io/deep-flux/apidocs/org/dual/hexa/ai/llm/port/in/IPromptEnhancer.html), [`IImageDescriber`](https://sdurz75.github.io/deep-flux/apidocs/org/dual/hexa/ai/llm/port/in/IImageDescriber.html).
