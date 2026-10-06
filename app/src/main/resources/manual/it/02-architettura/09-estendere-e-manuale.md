# Estendere il sistema e questo manuale

Liste di controllo per le modifiche più frequenti, come riusare il progetto come template e come si scrive e si aggiorna questo manuale.

## Aggiungere una pagina

1. Solo navigazione: un controller nell'adapter in `web` del sottosistema giusto (che parla solo con le porte in), un template `templates/app/<pagina>.html` con il layout manager, la voce nel menu (`fragments/app/nav.html`) e le breadcrumb.
2. Aggiornamento parziale: estrarre un fragment, che il controller restituisce se la richiesta è htmx.
3. Solo interattività locale: Alpine nel template, senza controller.
4. Se tocca un'entity: una nuova migrazione Flyway, mai `ddl-auto`.
5. Ogni testo visibile in entrambi i bundle; bottoni e select con i componenti, mai a mano.
6. Un evento che l'utente deve notare passa dal registro eventi (`warn` o `record`).

## Aggiungere un modello

Si censisce con un `INSERT` in `replicate_model`. Se ha parametri nuovi serve un tipo di form: un valore in `GenerationFormType`, il suo handler e il fragment `generation-params-<tipo>.html`; ogni campo del fragment deve comparire fra i valori di default dell'handler, altrimenti sparisce in silenzio (un test lo impone). Serve anche una regola di prezzo in `ReplicatePricing`, tranne che per i fine-tune LoRA. Infine, il manuale: la pagina [Genera immagini](../01-uso/03-genera-immagini.md) elenca i modelli.

## Aggiungere uno strumento alla chat

Una classe con metodi `@Tool` che implementa `IChatToolkit`, con un `@Order` e la chiave della sua sezione di prompt (`deep-chat.section.<nome>` in `prompts.properties`). Va aggiunta all'elenco di `ChatPromptTest`, che controlla il tetto di lunghezza, la coerenza delle sezioni e che ogni strumento citato esista. Se il bot deve poter citare un nuovo percorso dell'app, si aggiunge anche all'elenco chiuso di `linkAppPaths` in `deep-chat.html` e alla sezione `appmap`.

## Aggiungere un servizio remoto

Un valore nella sorgente degli eventi (core o app) con la sua etichetta nei due bundle; una eccezione che estende `RemoteServiceException`; un client che implementa la porta out del sottosistema, con ogni chiamata dentro `remote.call(...)` (`RetryPolicy.NONE` se non idempotente); e, nel chiamante in background, la registrazione dell'errore. Niente codice lato interfaccia: il toast è generico.

## Riusare il progetto come template

Per costruire un'altra webapp si tiene il **core** (compreso questo motore del manuale) e si sostituisce l'**app**. L'elenco di cosa tenere e cosa riscrivere è in `docs/TEMPLATE.md`. Per il manuale cambia il contenuto: i file in `src/main/resources/manual/`, e le etichette dei gruppi nel bundle dell'app.

## Come funziona questo manuale

Le pagine sono file Markdown nel jar, convertiti in HTML a ogni richiesta dal sottosistema `core.manual`; la porta è `IManual`.

### Dove stanno i file

```
src/main/resources/manual/<lingua>/<NN-gruppo>/<NN-pagina>.md
```

- La **lingua** è il codice a due lettere (`it`). Se per la lingua della richiesta non c'è una cartella, si ricade sull'italiano: aggiungere `manual/en/` basta a offrire il manuale in inglese.
- Il **gruppo** è la cartella senza il prefisso numerico (`01-uso` è `uso`). Il suo nome nell'indice è la chiave `manual.group.<gruppo>` del bundle dell'app. La parte **Uso** è anche quella che l'assistente legge dalla chat.
- La **pagina** si chiama con un prefisso numerico a due cifre, che ne decide l'ordine. Lo **slug**, cioè l'indirizzo `/manual/<slug>`, è il nome senza prefisso ed estensione, ed è unico fra tutti i gruppi.
- Il **titolo** è il primo titolo di primo livello, e il **riassunto** nell'indice è il primo paragrafo.

### Come si scrive

- Una sola intestazione `#` per pagina. I titoli di secondo livello (`##`) sono le **sezioni**: l'unità che l'assistente cerca e cita. Tienile abbastanza piccole da rispondere a una domanda.
- Nessuna formattazione dentro un titolo (niente grassetto o codice): serve a mantenere prevedibili le ancore.
- L'ancora di un titolo è il testo in minuscolo, senza accenti, con i trattini al posto dei segni: «Cos'è un LoRA?» diventa `cos-e-un-lora`; un titolo ripetuto prende `-1`, `-2`.
- Le etichette dell'interfaccia si scrivono in grassetto, come compaiono nell'app: si copiano dai file dei messaggi, ma con gli accenti veri (nei file è scritto `Intensita'`, nel manuale **Intensità**). Per un pulsante o un'icona vale il titolo che l'utente vede (**Anima in un video**, non «Anima»): si controlla nel template.
- Non scrivere numeri di porta né indirizzi che cambiano da un'installazione all'altra.

### I link

| Cosa | Come si scrive | Esempio |
|---|---|---|
| Un'altra pagina del manuale | il nome **vero** del file, con l'ancora facoltativa | `../01-uso/03-genera-immagini.md#parametri` |
| Una pagina dell'app | il percorso che parte dalla radice | `/gallery` |
| Una sezione della stessa pagina | solo l'ancora | `#parametri` |
| Un sito esterno | l'indirizzo completo | si apre in un'altra scheda |

I link ai file `.md` funzionano anche su GitHub e negli IDE. Il convertitore li riscrive per l'applicazione, e antepone ai percorsi dell'app il prefisso del reverse proxy, se c'è. L'HTML scritto dentro un file `.md` non viene eseguito, ma mostrato come testo.

### Tenerlo aggiornato

Una modifica di comportamento visibile all'utente aggiorna la pagina del manuale che la descrive. Un test (`ManualContentTest`) legge il contenuto vero e fallisce se un link a un'altra pagina o a un'ancora non esiste, se un percorso dell'app non corrisponde a una rotta reale, se due pagine hanno lo stesso slug o se un titolo contiene formattazione. Non può accorgersi di un testo diventato vecchio: quello resta a chi cambia il comportamento.
