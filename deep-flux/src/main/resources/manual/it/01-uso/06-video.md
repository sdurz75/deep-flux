# Video

I video si generano con la stessa pipeline delle immagini, e finiscono nella stessa [galleria](07-galleria-e-archivio.md). Oggi c'è un solo modello video, **p-video**, che crea un video **a partire da un'immagine** o **da una descrizione**.

## Animare un'immagine

Il modo più rapido: su qualunque miniatura (nella galleria, nel dettaglio di una generazione, nella galleria di una conversazione) l'icona **Anima in un video** apre il form video con quell'immagine già scelta come sorgente. Il prompt descrive il movimento che vuoi vedere. Anche il pulsante **Anima in un video** del dettaglio fa la stessa cosa.

La sorgente è quel file preciso. Se l'immagine non è riuscita, o non esiste più, l'app si ferma con un messaggio invece di fare in silenzio un video da testo.

## Il form video

Dal menu **Crea**, [Genera video](/generations/new?kind=video) apre il form senza sorgente. I campi principali:

- **Immagine di partenza (opzionale)**: la carichi dal disco (PNG, JPEG o WebP, fino a dieci megabyte). Se non la dai, il video nasce solo dal prompt.
- **Prompt**, con **Migliora il prompt con l'AI**: se c'è una sorgente, l'AI la guarda e scrive il prompt di movimento.
- **Durata (secondi)**, **Risoluzione** e **Frame al secondo**.
- **Bozza (più veloce, qualità minore)** per provare in fretta.
- **Migliora il prompt**, la riscrittura automatica del modello.

Il file caricato ha la precedenza su un'eventuale sorgente scelta dall'icona **Anima in un video**, ed è eliminato insieme alla generazione.

## Dopo la generazione

Un video richiede più tempo di un'immagine: l'attesa massima è di quindici minuti invece di cinque. Nel dettaglio e in galleria il video si riproduce direttamente nella pagina, non c'è l'ingrandimento a schermo come per le immagini. Come le immagini, ha la stella dei preferiti, i tag, il costo stimato e **Usa configurazione**.

## Cosa non c'è

La chat non genera video e non offre modelli video; ti spiega la strada o propone un bottone di animazione (**Anima #12**) su un'immagine. Non c'è audio da cui partire (audio-to-video) e un video non si può usare come sorgente di una nuova generazione.
