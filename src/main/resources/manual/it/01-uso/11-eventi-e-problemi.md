# Eventi e problemi

Quando qualcosa non va, l'app non ti lascia con una pagina muta: registra l'evento, ti avvisa con un riquadro in basso e lo conserva in una pagina dove puoi rileggerlo.

## La campanella

In alto a destra, la campanella si accende (con un numero) quando ci sono eventi **non ancora visualizzati**: rossa se c'è un errore, gialla se ci sono solo avvisi. Cliccandola vedi gli ultimi cinque; cliccando un evento si apre la sua pagina e viene segnato come visualizzato; **Segna tutti come letti** li azzera; **Vedi tutti** apre l'elenco completo.

Un evento che si ripete a distanza ravvicinata non riaccende la campanella a ogni volta: le ripetizioni vengono raggruppate.

## Gli eventi di sistema

La pagina [Eventi](/system/events), nel menu **Gestione**, è il registro. Ogni riga ha una **gravità** (**Errore** o **Avviso**), una **fonte** (Replicate, OpenRouter, SearXNG, Archivio, Token, LoRA, Interno), cosa stava succedendo, e quante volte si è ripetuta. **Dettagli** mostra il messaggio completo; dove ha senso c'è un link per aprire la generazione o la conversazione coinvolta.

Il filtro **Filtra per gravità** limita l'elenco a **Errori** o **Avvisi**. **Svuota** cancella il registro (chiede conferma).

Gli **avvisi** non sono errori: oggi servono per i token in scadenza o scaduti. Gli errori nascono da chiamate a servizi esterni (Replicate, OpenRouter, il motore di ricerca web) o da un guasto interno.

## Cosa fare, in ordine

1. Leggi il riquadro che compare in basso: di solito dice già cosa è successo.
2. Se non basta, apri [Eventi](/system/events) e leggi i **Dettagli**.
3. Se ti dice «riprova tra qualche istante», è un guasto momentaneo del servizio: riprova dopo un po'. Le generazioni a pagamento non vengono mai ripetute da sole, per non fatturarle due volte.
4. Se l'assistente è raggiungibile, puoi chiedergli «perché è fallita la generazione #12?»: sa leggere gli eventi recenti e il dettaglio della generazione.

## La generazione non parte

- **Il token di Replicate manca** (messaggio che nomina la variabile REPLICATE_API_TOKEN): va impostato sul server prima dell'avvio. Navigare e cercare funzionano anche senza.
- **Troppe generazioni già in corso per questo modello**: aspetta che finiscano o interrompile, poi riprova.
- **Modello non censito nel catalogo**: il modello scelto non è più nell'elenco; scegline un altro.

## La generazione è fallita

- **Generazione annullata.**: l'hai interrotta tu con **Interrompi**.
- **Timeout: nessuna risposta da Replicate dopo N minuti**: il servizio non ha finito in tempo (cinque minuti per le immagini, quindici per i video). Riprova, magari con un modello più rapido.
- **Prediction completata ma senza output utilizzabile**, **risultato non scaricabile**: Replicate ha risposto ma il file non è arrivato. Di solito basta riprovare.
- Un messaggio di Replicate con un codice (per esempio 4xx): il servizio ha rifiutato la richiesta, spesso per un parametro non valido o per il contenuto del prompt. Leggi il testo e cambia il prompt o i parametri.

## Immagine di partenza e maschera

- **Serve un'immagine da modificare: caricane una o parti da una generazione**: il modello scelto (Kontext, Fill) parte da un'immagine e non l'hai data.
- **Per l'inpainting serve una maschera: disegna la zona da rigenerare**: con flux-fill-dev e flux-fill-pro la maschera è obbligatoria.
- **Una maschera serve solo con un'immagine di partenza**: hai una maschera ma nessuna sorgente; carica l'immagine o rimuovi la maschera.
- Se la sorgente da animare non si legge, l'immagine non è riuscita o non c'è più: scegline un'altra.

## Token e LoRA

- **Il token scelto non esiste più** / **è scaduto**: rinnovalo nella pagina [Token](/tokens) o scegline un altro. L'app si ferma prima di chiamare Replicate.
- **Chiave di cifratura mancante o non valida**: la pagina dei token non può salvare né leggere segreti. L'amministratore deve impostare la chiave nella configurazione del server (vedi [Dati, storage e sicurezza](../02-architettura/07-dati-storage-sicurezza.md)).
- **Impossibile decifrare il token**: la chiave attuale è diversa da quella con cui fu salvato. Reinseriscilo.
- **Non è un LoRA di Flux**: la sorgente indicata non ha il campo di intensità tipico di un LoRA; il LoRA salvato resta, ma non diventa un modello.

## Servizi non raggiungibili

- **Impossibile contattare Replicate / OpenRouter / SearXNG**: il servizio non risponde, o la rete è giù. Riprova.
- La chat risponde con un messaggio in rosso e nessuna risposta: il modello linguistico non è stato raggiungibile. Non viene rimandato al modello in seguito, quindi basta ripetere la domanda.
- Il credito mostra **n/d**: il servizio dei crediti non risponde in questo momento.

## L'archivio e la ricerca

- Un'immagine riuscita non è in [Galleria](/gallery): controlla lo stato in [Generazioni](/generations); in galleria compaiono solo le riuscite.
- Un'immagine importata non esce nelle ricerche: la sua analisi è fallita; premi **Riprova l'analisi** nel dettaglio.
- Cercando non trovi una cosa appena creata: l'indice si aggiorna entro pochi istanti; **Riconcilia ora** in [Ricerca](/search) lo forza.

## Quando contattare chi gestisce il sistema

Se un problema persiste, o se vedi errori che nominano il database, lo storage dei file, le chiavi o la configurazione, chi amministra il sistema trova le informazioni in [Operatività](../02-architettura/08-operativita.md).
