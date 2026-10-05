# Archivio e ricerca

L'archivio è quello che resta dopo le generazioni, le importazioni e le conversazioni; la ricerca per significato sta in `app.search`. Questa pagina spiega come viene indicizzato e perché è fatto così.

## Una sola base di dati

La ricerca usa lo stesso PostgreSQL dei dati, con l'estensione **pgvector**: nessun servizio in più. Il sottosistema espone solo tipi di dominio (`SearchableDocument`, `ScoredDocument`, `DocumentFilter`...) e **non lascia uscire** Spring AI dal suo adapter: chi vuole cercare usa `IArchiveSearch`, l'indice sta dietro la porta out `IVectorIndex`, implementata da `PgVectorIndex` sopra `PgVectorStore`. Un domani si potrebbe cambiare il motore cambiando solo quell'adapter.

## Gli embedding

Il modello è locale (`TransformersEmbeddingModel`, ONNX): **multilingual-e5-small** quantizzato, 384 dimensioni, italiano e inglese, circa 120 megabyte. Modello e tokenizer si scaricano una volta al primo avvio da Hugging Face nella cartella `./data/models`, fuori da git. I modelli e5 vogliono un prefisso diverso per i documenti (`passage:`) e per le ricerche (`query:`): lo applica un decoratore (`E5PrefixEmbeddingModel`) che solo lo store vede, chi lo usa passa il testo nudo.

Due conseguenze pratiche:

- I punteggi sono **compressi** (di solito 0,7–0,9): per questo la pagina di ricerca usa una soglia alta di default (80 per cento) e la chat usa i primi K risultati, non soglie fisse.
- Il punteggio è `1 - distanza coseno`. **Non c'è un indice approssimato** (HNSW o IVFFlat) di proposito: `/search` vuole tutta la classifica sopra soglia, e un indice approssimato la troncherebbe; lo scan esatto costa pochi millisecondi anche a decine di migliaia di righe.

Cambiare modello con dimensioni diverse da 384 richiede una nuova migrazione e la reindicizzazione completa.

## Cosa entra nell'indice

Un documento ricercabile è una `SearchableDocument`. Chi li fornisce sono le implementazioni di `ISearchableSource`, un'interfaccia di estensione definita da `search` e implementata dagli altri sottosistemi, che così non sono conosciuti dalla ricerca:

- `GenerationSearchSource` (in `generation`): **un documento per generazione** riuscita, non uno per file. Il testo indicizzato è il prompt più, dopo un separatore, alcune parole chiave d'indice in italiano e in inglese (tipo di media, modello, orientamento, risoluzione, nomi dei LoRA...). Quelle parole servono a trovare le cose, non sono testo per l'utente e la pagina non le mostra.
- le **immagini importate**, come tipo proprio, **solo se l'analisi è completata**;
- `ChatSearchSource` (in `chat`): i messaggi non d'errore e i titoli delle conversazioni;
- le **note** manuali, le uniche creabili e modificabili a mano.

Ogni documento ha dei metadati: tipo, identificativo, conversazione, data di creazione e altri; più, scritte dall'indicizzatore, un hash del contenuto, l'identificativo del modello di embedding e la data di indicizzazione. I **tag dell'utente** stanno nei metadati e **non** nel testo che si trasforma in vettore: il filtro è per tag esatto, e un cambio di tag riscrive solo i metadati, senza ri-embeddare.

## L'indicizzazione

`VectorIndexer` è il punto unico di scrittura: salta i documenti invariati (stesso hash e stesso modello), riscrive solo i metadati se cambiano solo quelli, altrimenti ricalcola il vettore. `ArchiveIndexService` **allinea** l'indice con una riconciliazione idempotente, non con ganci su ogni salvataggio: chiede a tutte le sorgenti i loro documenti, aggiunge i mancanti o cambiati e rimuove quelli la cui riga non esiste più. Gira:

- all'avvio, come ricostruzione iniziale;
- ogni cinque minuti;
- subito dopo un evento che cambia i dati indicizzati (generazione finita, file o generazione cancellati, stella, tag), dopo il commit, in background.

Un documento che fallisce viene registrato negli eventi e non ferma gli altri. Le note, che esistono solo nell'indice, non vengono mai create né rimosse dalla riconciliazione.

## Le immagini importate

Un'immagine importata passa da `IImportedImages`: la riga nasce subito riuscita, e l'**analisi** del contenuto parte in background (`ImportAnalysisListener`, due thread). L'analisi è idempotente e usa `IImageDescriber`, che chiama un modello di visione per ottenere descrizione e tag. Un rifiuto del modello o una risposta illeggibile segnano l'analisi come fallita senza notifiche; un guasto del servizio la segna fallita e registra un evento. Un controllo periodico riprende le analisi rimaste a metà da più di cinque minuti.

## Filtri e tag

Il filtro (`DocumentFilter`: tipo, periodo, tipo di media, solo preferiti, tag) viene tradotto da `PgVectorIndex` in un'espressione che Spring AI trasforma in un percorso JSON. Gli operatori supportati sono uguaglianza, appartenenza, confronti e AND e OR; non esiste un NOT. La normalizzazione dei tag è unica (`Tags`): minuscolo, spazi collassati, niente virgole, virgolette o barre inverse (finirebbero nel filtro), al massimo quaranta caratteri e venti tag per elemento.

## Disattivare la ricerca

Con la proprietà `app.search.enabled=false` indice, pianificatore, strumenti della chat, servizi e modello di embedding si spengono, e il modello non viene mai scaricato. È l'impostazione dei test; con la ricerca spenta la voce **Ricerca** non compare nel menu. Per l'uso si veda [Ricerca e note](../01-uso/09-ricerca-e-note.md).
