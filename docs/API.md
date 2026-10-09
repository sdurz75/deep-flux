# API pubbliche di hexa

Indice delle porte `port.in` (capability che un host usa e SPI che implementa) delle librerie, con il link al sorgente: il commento di ogni interfaccia e' la documentazione di riferimento. `ApiIndexTest` (modulo `deep-flux`) fallisce se un file elencato non esiste o se una porta `port.in` non e' elencata. Per le regole su chi puo' dipendere da cosa vedi [`CLAUDE.md`](../CLAUDE.md), sezione Architettura.

## hexa-core

### `backup`

- [`IBackupExport`](../hexa-core/src/main/java/org/dual/hexa/core/backup/port/in/IBackupExport.java): Esporta DB e binari in UN archivio (comando `export` del jar, profilo `backup`).
- [`IBackupImport`](../hexa-core/src/main/java/org/dual/hexa/core/backup/port/in/IBackupImport.java): Ripristina un archivio nel DB e nello storage della configurazione corrente (comando `import` del jar).
- [`IBlobReferences`](../hexa-core/src/main/java/org/dual/hexa/core/backup/port/in/IBlobReferences.java): SPI: dice al backup dove il DB tiene i nomi dei binari dello storage.
### `config`

- [`IConfigModule`](../hexa-core/src/main/java/org/dual/hexa/core/config/port/in/IConfigModule.java): SPI di un modulo che ha bisogno di configurazione: basta un bean (si autoregistra, come `ILayoutContributor`) e la pagina `/settings` e la voce del menu «Gestione» compaiono da sole.
- [`IModuleSettings`](../hexa-core/src/main/java/org/dual/hexa/core/config/port/in/IModuleSettings.java): Lettura e scrittura della configurazione dei moduli registrati (`IConfigModule`).
### `events`

- [`INotifications`](../hexa-core/src/main/java/org/dual/hexa/core/events/port/in/INotifications.java): Notifiche EFFIMERE all'utente (toast di successo), su qualunque pagina aperta: l'esito positivo di qualcosa che e' finito in background (una generazione, un training, un import).
- [`ISystemEvents`](../hexa-core/src/main/java/org/dual/hexa/core/events/port/in/ISystemEvents.java): Unico punto di registrazione degli eventi di sistema (vedi CLAUDE.md, "Errori ed eventi di sistema"): gli ERRORI delle chiamate remote e quelli interni non gestiti (`record`: ogni `catch` che riguarda un servizio este...
### `lock`

- [`ILock`](../hexa-core/src/main/java/org/dual/hexa/core/lock/port/in/ILock.java): Blocco dell'app con PIN dopo un periodo di inattivita'. Senza PIN impostato il blocco e' spento e non cambia nulla (`#isEnabled()` falso).
- [`ILockExemptPaths`](../hexa-core/src/main/java/org/dual/hexa/core/lock/port/in/ILockExemptPaths.java): SPI per le librerie opzionali: path che il cancello del blocco lascia passare anche a sessione bloccata (es.
### `manual`

- [`IManual`](../hexa-core/src/main/java/org/dual/hexa/core/manual/port/in/IManual.java): Il manuale online: pagine Markdown (una cartella per lingua, una sottocartella per gruppo), convertite in HTML al volo.
### `push`

- [`IClientPush`](../hexa-core/src/main/java/org/dual/hexa/core/push/port/in/IClientPush.java): Invia un evento a tutte le tab connesse a `GET /events`.
- [`IClientPushStream`](../hexa-core/src/main/java/org/dual/hexa/core/push/port/in/IClientPushStream.java): Il flusso dei messaggi per UNA tab (una sottoscrizione a `GET /events`), con buffer proprio.
### `secrets`

- [`ISecretCipher`](../hexa-core/src/main/java/org/dual/hexa/core/secrets/port/in/ISecretCipher.java): Cifratura dei segreti salvati nel DB (token API, ...).
- [`ISecrets`](../hexa-core/src/main/java/org/dual/hexa/core/secrets/port/in/ISecrets.java): CRUD dei segreti salvati (cifrati nel DB) e loro risoluzione in chiaro per chi li usa.
### `storage`

- [`IBlobMigration`](../hexa-core/src/main/java/org/dual/hexa/core/storage/port/in/IBlobMigration.java): Migrazione una tantum dei binari verso un altro backend (oggi: locale -> WebDAV cifrato).
- [`IImageStorageService`](../hexa-core/src/main/java/org/dual/hexa/core/storage/port/in/IImageStorageService.java): Persistenza dei binari dell'app (immagini, video mp4, upload sorgente): l'unico punto da cui li si scrive, li si legge e li si serve (vedi ImageController per `/images/**`).

## hexa-ai

### `chat`

- [`IChat`](../hexa-ai/src/main/java/org/dual/hexa/ai/chat/port/in/IChat.java): Un turno di conversazione con l'assistente.
- [`IChatConversations`](../hexa-ai/src/main/java/org/dual/hexa/ai/chat/port/in/IChatConversations.java): Le conversazioni di /deep-chat: elenco, creazione, rinomina, cancellazione, cronologia.
- [`IChatOutcomeResolver`](../hexa-ai/src/main/java/org/dual/hexa/ai/chat/port/in/IChatOutcomeResolver.java): SPI (facoltativa) con cui chi possiede il dominio degli esiti dice alla chat come presentarli: la chat conosce solo il riferimento opaco di un turno (`ChatMessage#getOutcomeRef`).
- [`IChatOutcomes`](../hexa-ai/src/main/java/org/dual/hexa/ai/chat/port/in/IChatOutcomes.java): Scrittura dell'esito asincrono di qualcosa che la chat ha avviato (oggi una generazione) come nuovo turno della conversazione.
- [`IChatPageContributor`](../hexa-ai/src/main/java/org/dual/hexa/ai/chat/port/in/IChatPageContributor.java): SPI (zero o piu' implementazioni) con cui chi ospita la chat aggiunge al model della pagina `/deep-chat/{id`} cio' che i suoi fragment si aspettano (per l'app: modelli e form di generazione, placeholder da ripristinar...
- [`IChatToolkit`](../hexa-ai/src/main/java/org/dual/hexa/ai/chat/port/in/IChatToolkit.java): Un gruppo di tool dell'assistente (una classe con metodi `@Tool`).
- [`IChatTurnContributor`](../hexa-ai/src/main/java/org/dual/hexa/ai/chat/port/in/IChatTurnContributor.java): SPI (zero o piu' implementazioni) con cui chi ospita la chat compone il contesto di ogni turno a partire dalle impostazioni opache che il client manda con il messaggio (oggi: modello e parametri di generazione scelti...
### `credits`

- [`ICreditSource`](../hexa-ai/src/main/java/org/dual/hexa/ai/credits/port/in/ICreditSource.java): SPI dell'host: una sorgente di righe di credito (per l'app la stima del saldo Replicate).
- [`ICredits`](../hexa-ai/src/main/java/org/dual/hexa/ai/credits/port/in/ICredits.java): Credito residuo sui servizi a pagamento per la barra in basso: le righe di OpenRouter (core) piu' quelle che l'host contribuisce con le proprie `ICreditSource`.
### `llm`

- [`IAiCalls`](../hexa-ai/src/main/java/org/dual/hexa/ai/llm/port/in/IAiCalls.java): Esecutore delle chiamate al provider LLM (`ChatClient`), condiviso dagli adapter AI della chat e dei prompt: da' a ogni errore il tipo, la source e il `Kind` comuni (`OpenRouterException`) e NON ritenta mai: Spring AI...
- [`IImageCaptioner`](../hexa-ai/src/main/java/org/dual/hexa/ai/llm/port/in/IImageCaptioner.java): La didascalia di un'immagine di addestramento con un modello di visione: una riga di prosa inglese che nomina la trigger word.
- [`IImageDescriber`](../hexa-ai/src/main/java/org/dual/hexa/ai/llm/port/in/IImageDescriber.java): Analisi di contenuto di un'immagine con un modello di visione: una descrizione in prosa e dei tag, per indicizzarla semanticamente.
- [`IPromptEnhancer`](../hexa-ai/src/main/java/org/dual/hexa/ai/llm/port/in/IPromptEnhancer.java): "AI enhance": riscrittura one-shot di una bozza di prompt (anche in italiano) in un prompt ben formato.
### `search`

- [`IArchiveIndex`](../hexa-ai/src/main/java/org/dual/hexa/ai/search/port/in/IArchiveIndex.java): Tiene l'indice semantico allineato ai dati delle `ISearchableSource`.
- [`IArchiveNotes`](../hexa-ai/src/main/java/org/dual/hexa/ai/search/port/in/IArchiveNotes.java): Note manuali: l'unico tipo di documento creabile/modificabile/eliminabile dall'utente (gli altri sono derivati).
- [`IArchiveSearch`](../hexa-ai/src/main/java/org/dual/hexa/ai/search/port/in/IArchiveSearch.java): Interrogare l'archivio: ricerca per significato, sfogliare, ispezionare.
- [`ISearchableSource`](../hexa-ai/src/main/java/org/dual/hexa/ai/search/port/in/ISearchableSource.java): Punto di estensione: chi possiede dei dati da rendere ricercabili (generazioni, chat...) implementa questa interfaccia come bean.

## hexa-pwa

### `shell`

- [`IPwa`](../hexa-pwa/src/main/java/org/dual/hexa/pwa/shell/port/in/IPwa.java): Cio' che serve a un browser per installare l'app e tenerne la shell offline.

## hexa-oauth2

### `login`

- [`IOAuthAccess`](../hexa-oauth2/src/main/java/org/dual/hexa/oauth2/login/port/in/IOAuthAccess.java): Il cancello di accesso con OAuth2/OIDC. Spento (default) non cambia nulla nell'app.
- [`IOAuthProviders`](../hexa-oauth2/src/main/java/org/dual/hexa/oauth2/login/port/in/IOAuthProviders.java): I provider OIDC: quello d'ambiente (primo, in sola lettura) e quelli salvati nelle impostazioni del modulo.
