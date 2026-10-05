# Generazione e Replicate

Cosa succede tra il clic su **Genera** e l'immagine in galleria. Il codice sta nel sottosistema `app.generation`.

## La riga Generation

Ogni generazione, dal form o dalla chat, è una riga `Generation`: il modello, il prompt, i parametri nel vocabolario di Replicate, lo stato, i nomi dei file prodotti, il costo stimato, il collegamento a una conversazione e a una eventuale sorgente. Gli stati sono `PENDING`, `PROCESSING`, `SUCCEEDED` e `FAILED`; il tipo è immagine o video. Anche un'immagine importata è una `Generation` (con origine `IMPORTED`, subito riuscita e senza modello): per questo galleria, stella, tag e ricerca sono gli stessi.

## Dal form alla prediction

1. Il controller converte i campi del form in parametri con `IGenerationForms` (un **handler** per tipo di form, `IGenerationParameterHandler`, con parsing tollerante) e li passa a `IGenerations#create` come mappa tipizzata: l'esagono non vede mai JSON né campi di form. La stessa conversione serve alla chat.
2. `GenerationService#create` verifica il modello nel catalogo (`IModelCatalog`, la tabella `replicate_model`), salva eventuali upload (sorgente, maschera), manda la sorgente come indirizzo dati, risolve i token dei LoRA privati (`TokenInputResolver`, sopra `IApiTokens`) e chiama Replicate dalla porta `IPredictionGateway`.
3. La chiamata che crea la prediction non ha **mai** un retry (`RetryPolicy.NONE`): un secondo tentativo potrebbe fatturare due volte.
4. Per modello c'è un limite di prediction contemporanee: oltre, `TooManyPredictionsException`.
5. Se il salvataggio della riga fallisce dopo aver creato la prediction, la prediction viene annullata.

## Come si avanza fino alla fine

Una prediction non ha callback: si interroga.

- Dal form, la pagina `/generations/{id}` fa polling htmx ogni due secondi finché lo stato non è terminale.
- Dalla chat, un `ChatGenerationWatcher` in background interroga Replicate e, a fine lavoro, scrive il turno di esito e lo spinge alla scheda via SSE: nessun polling nel browser.
- A ogni interrogazione `IGenerations#refresh` fa avanzare lo stato; alla riuscita scarica i file nello storage (con il nome opaco) e pubblica `GenerationCompletedEvent`, che aggiorna galleria e indice di ricerca.

## Nessuno stato indefinito

Ci sono tre reti, perché una generazione non deve restare «in corso» per sempre:

1. **`refresh` non lascia stati parziali**: download e post-elaborazione in un try/catch, e in caso di errore `FAILED` con i file ripuliti. Un errore transitorio di polling (rete, timeout, 429, 5xx) non fa fallire la generazione; uno permanente (4xx) sì. Il timeout di business (cinque minuti per le immagini, quindici per i video) vale comunque e annulla la prediction.
2. **`GenerationRecoveryService`** all'avvio fa avanzare ogni generazione in corso, e poi ogni pochi minuti chiude quelle oltre il timeout.
3. **`ChatRecoveryService`** riavvia i watcher persi e scrive i turni di esito mancanti (in modo idempotente).

Cancellare o far scadere una generazione in corso annulla anche la prediction.

## Modelli e form-type

Il catalogo dei modelli sta nel database, non nella configurazione: un modello nuovo si censisce con un `INSERT` in `replicate_model` (proprietario, nome, versione facoltativa, tipo di form, ordine). Il **tipo di form** (`GenerationFormType`) decide i campi, la chiave dell'immagine sorgente, se serve una maschera, il tipo di output; ogni tipo ha il proprio handler e il proprio fragment `generation-params-<tipo>.html`. I fine-tune LoRA di Replicate condividono un unico tipo generico: se ne aggiunge uno con un `INSERT`, senza codice.

Il numero di immagini per richiesta è limitato a quattro, sia dal campo che dal server (la chat non passa dalla validazione HTML).

## Costo

Replicate espone solo le metriche. `ReplicatePricing`, una classe di regole nel domain, ne ricava un costo **stimato** con una regola per ogni modello censito (prezzo fisso o tempo di calcolo); i fine-tune sono stimati dal tipo di form. Il costo viene salvato in `generation.cost_usd` quando la generazione finisce e alimenta il credito della barra in basso.

## Errori e registro eventi

Ogni chiamata remota passa da un esecutore unico (`RemoteCaller`) che classifica l'errore: `TRANSIENT` (ritentabile), `PERMANENT`, `CONFIGURATION` (token mancante), `REJECTED` (rifiuto atteso). Solo i primi tre finiscono nel registro eventi. Vedi [Dati, storage e sicurezza](07-dati-storage-sicurezza.md) per i dettagli sui servizi esterni, e [Chat e assistente](05-chat-e-assistente.md) per il percorso dal lato chat.
