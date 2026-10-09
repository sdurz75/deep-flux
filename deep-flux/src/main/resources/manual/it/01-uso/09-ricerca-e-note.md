# Ricerca e note

[Ricerca](/search) trova le cose **per significato**, non per parole esatte: scrivendo «un gatto che dorme» ritrovi le immagini il cui prompt o la cui descrizione parla di un gatto addormentato, anche se non contengono quelle parole. Funziona in italiano e in inglese. La voce compare nel menu solo se la ricerca semantica è attiva.

## Cosa si ricerca

L'archivio indicizza:

- le **generazioni** riuscite (il prompt e informazioni come modello, tipo, orientamento e LoRA);
- le **immagini importate** con l'analisi completata (descrizione e tag): vedi [Importare immagini](08-importare-immagini.md);
- i **messaggi** delle conversazioni e i loro **titoli**;
- le **note** manuali.

L'indice si tiene aggiornato da solo, in background e a ogni nuova generazione.

## La pagina di ricerca

C'è un solo form, che si ri-invia mentre scrivi e cambi i filtri. Per rileggere la lista con i filtri correnti c'è l'icona **Aggiorna** accanto al titolo; da telefono basta tirare la pagina verso il basso, in cima, e rilasciare:

- **Cerca**: il testo (per esempio «un gatto che dorme»).
- **Tipo**: **Tutti**, **Generazioni**, **Importate**, **Messaggi**, **Conversazioni**, **Note**.
- **Media**: **Tutti**, **Immagini**, **Video**; **Solo preferiti**. Valgono solo per generazioni e immagini importate: scegliendoli, messaggi, conversazioni e note sono esclusi.
- **Dal** / **Al**: il periodo di creazione.
- **Tag**: il tag esatto (vedi [Tag](07-galleria-e-archivio.md#tag)).
- **Somiglianza minima (%)**: una soglia, solo quando c'è del testo. Parte da 80, perché i punteggi di questo tipo di ricerca sono compressi (di solito fra 70 e 90): una soglia più bassa lascia passare più rumore, una più alta può svuotare i risultati.
- Ogni campo (testo, tag, date) ha una piccola **x** per svuotarlo; compare solo quando contiene qualcosa.
- **Azzera filtri** torna alla pagina iniziale.

**Con del testo** vedi la classifica per somiglianza, con il punteggio in percentuale: tutto quello che supera la soglia; scorrendo in fondo se ne caricano altri da soli. **Senza testo** si sfogliano i documenti, i più recenti per primi, filtrati per tipo, periodo e tag.

Ogni riga mostra il tipo, il testo, le eventuali miniature (generazioni e importate, con la stella e i tag in sola lettura) e un pulsante per aprire la generazione, l'immagine importata o la conversazione. **Dettagli** mostra id, metadati e hash del documento.

## Le note

Una **nota** è un documento tuo, scritto a mano: entra nella ricerca e nel tool dell'assistente come tutto il resto. Servono per annotare idee, istruzioni, preferenze: «lo stile del cliente X: pastello, luce morbida».

- **Nuova nota** apre una finestra con **Titolo (opzionale)** e **Testo**; **Aggiungi nota** la salva.
- **Modifica** ed **Elimina** (con conferma) valgono **solo per le note**. Gli altri documenti (generazioni, messaggi, conversazioni) sono in sola lettura: la loro fonte di verità è l'archivio, e una modifica a mano verrebbe annullata al giro successivo.
- L'assistente può salvare una nota, ma solo se glielo chiedi.

## Lo stato dell'indice

Nella pagina trovi quanti documenti ci sono (**Totale**), le **Dimensioni** dei vettori e il **Modello di embedding** usato. **Riconcilia ora** forza un riallineamento immediato con l'archivio. Per un documento derivato **Ri-embedda** ricalcola il suo vettore, utile dopo aver cambiato il modello.

## Primo avvio

La ricerca usa un modello locale (multilingue, circa 120 megabyte) che l'app scarica una volta, al primo avvio, che può quindi richiedere un po' di tempo. Dopo non servono né rete né costi.
