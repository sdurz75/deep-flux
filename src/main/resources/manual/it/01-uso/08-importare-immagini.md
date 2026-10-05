# Importare immagini

L'archivio non contiene solo immagini generate: puoi portarci le tue. Un'immagine importata si comporta come le altre (galleria, stella, tag, ricerca, uso come sorgente), con una differenza: non c'è stata nessuna generazione, quindi non ha modello, costo né seed.

## Come si importa

Dal menu **Crea**, [Importa immagini](/import) apre la pagina di importazione. Puoi:

- trascinare le immagini nella zona **Trascina qui le immagini, incollale o scegli dal disco**;
- cliccarla per scegliere i file dal disco;
- incollare un'immagine dagli appunti.

Vedi le anteprime, puoi toglierne con **Rimuovi dall'elenco**, poi premi **Importa**. Sono accettati PNG, JPEG e WebP, fino a dieci megabyte ciascuno e al massimo venti file alla volta. Se un file non va bene (tipo non supportato, troppo grande) viene rifiutato da solo e gli altri vengono importati comunque: alla fine leggi quante sono state importate e quante rifiutate.

Le importate si vedono anche nella scheda **Importate** della [Galleria](/gallery).

Importare lo stesso file due volte crea due immagini: non c'è un controllo dei doppioni.

## L'analisi del contenuto

Appena importata, ogni immagine viene **analizzata in background** da un modello di visione, che scrive una **descrizione** in italiano e una serie di **tag** in italiano e inglese. Servono a ritrovarla con la [ricerca per significato](09-ricerca-e-note.md) anche se non hai mai scritto nessun prompt.

Nell'elenco **Ultime importate** e nel dettaglio vedi **Analisi del contenuto in corso...** finché non finisce; il riquadro si aggiorna da solo. Se l'analisi non riesce (per esempio il modello rifiuta l'immagine, o il servizio non risponde) l'immagine resta comunque nell'archivio e utilizzabile come sorgente, ma non compare nella ricerca. Il pulsante **Riprova l'analisi** la rilancia. Un'analisi rimasta a metà viene ripresa dall'app da sola.

## Il dettaglio di un'immagine importata

[/import/{id}](/import) mostra l'immagine, la sua analisi (descrizione e tag), la data di importazione e queste azioni:

- **Usa la descrizione come prompt** la copia nel form, per generare qualcosa di simile;
- **Anima in un video**, **Usa come sorgente (img2img)**, la stella e i tag, come per le altre immagini;
- **Elimina immagine**.

Non ci sono prompt, modello, costo o seed da mostrare.

## Usarle come sorgente

Un'immagine importata si può usare come punto di partenza per un'immagine (img2img, Kontext, inpainting: vedi [Modifica e inpainting](04-modifica-e-inpainting.md)) o per un video ([Genera video](06-video.md)). Nei form con una sorgente il pulsante **Scegli dall'archivio** elenca anche le importate.
