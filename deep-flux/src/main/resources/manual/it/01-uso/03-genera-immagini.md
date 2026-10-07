# Genera immagini

Il form diretto, [Genera immagine](/generations/new), è la strada senza chatbot: scegli un modello, scrivi il prompt, regola i parametri e premi **Genera**. Lo si apre dal menu **Crea**. Per ritoccare un'immagine esistente vedi [Modifica e inpainting](04-modifica-e-inpainting.md); per i video, [Genera video](06-video.md).

## Il modello

Il campo **Modello** elenca i modelli censiti nel catalogo. Cambiando modello cambiano i campi che il form mostra, perché ogni modello ha i suoi parametri. In breve:

| Modello | A cosa serve |
|---|---|
| flux-lora-ff3 | il LoRA addestrato di default, per immagini nel suo stile |
| flux-2-klein-9b | generazione molto rapida (quattro passi) |
| flux-krea-dev | fotorealismo |
| flux-dev-lora | FLUX con LoRA caricabili al volo, anche a partire da un'immagine |
| flux-kontext-dev | modifica un'immagine con un'istruzione (richiede un'immagine) |
| flux-fill-dev, flux-fill-pro | inpainting: ridipingono la zona di una maschera (richiedono immagine e maschera) |

I modelli segnati **richiede un'immagine** partono da una sorgente: se ne parla in [Modifica e inpainting](04-modifica-e-inpainting.md). L'elenco può cambiare nel tempo; l'assistente di [Deep Chat](02-deep-chat.md) sa sempre dirti quali modelli ci sono.

## Il prompt

Scrivi nel campo **Prompt** quello che vuoi vedere, in prosa naturale. Due aiuti:

- **Migliora il prompt con l'AI** riscrive la tua bozza in un prompt più efficace (serve una bozza). Il risultato sostituisce il testo nel campo e puoi sempre modificarlo; se il servizio rifiuta, il testo non viene toccato. Con un'immagine di partenza l'AI la guarda per adattare il prompt.
- **Svuota il prompt** cancella il testo (compare solo se c'è del testo).

## Parametri

I campi variano per modello. I più comuni:

- **Numero di immagini**: da una a quattro per generazione.
- **Formato** (aspect ratio), **Larghezza** e **Altezza**, o i **Megapixel**: la dimensione dell'immagine.
- **Passi di inferenza** e **Guidance scale**: qualità e aderenza al prompt. Ogni modello ha i propri valori ragionevoli e il proprio intervallo.
- **Seed**: un numero che rende ripetibile il risultato. Vuoto significa casuale (il valore normale); il pulsante **Torna a casuale** lo svuota.
- Altri campi specifici: **Qualità**, **Go fast**, l'intensità dei LoRA e la casella **Migliora il prompt**, che fa riscrivere il prompt dal modello stesso (da non confondere col pulsante **Migliora il prompt con l'AI**, che lo riscrive prima, nel form).

Accanto a ogni campo numerico, quando il valore è diverso dal predefinito, compare un pulsante **Ripristina il valore predefinito**. Il campo **Version** (facoltativo) serve a bloccare una versione precisa del modello incollando il suo hash; lascialo vuoto per usare l'ultima.

**Reimposta modello e parametri ai default** riporta tutto ai valori iniziali.

### Le impostazioni si ricordano

Modello, parametri e prompt restano nel tuo browser: tornando alla pagina li ritrovi. Cambiano solo quando li modifichi tu o premi il pulsante di reimpostazione. Il form delle immagini e quello dei video ricordano ciascuno i propri.

## Generare

Premendo **Genera** si apre la pagina della generazione. Mentre lavora vedi un riquadro segnaposto per ogni immagine richiesta e un solo pulsante **Interrompi** (chiede conferma): fermarla la fa finire come annullata. La pagina si aggiorna da sola ogni due secondi.

Alcune cose da sapere:

- Ogni generazione costa denaro su Replicate. Una prova da una sola immagine è il modo più economico di provare un prompt.
- Se per lo stesso modello ci sono già troppe generazioni in corso, l'app te lo dice e va ritentato tra poco.
- Le immagini si possono generare solo con un token Replicate valido nella configurazione del server: senza, la generazione fallisce con un messaggio chiaro.

## Il dettaglio di una generazione

A generazione finita, la stessa pagina mostra:

- il **Prompt**, il **Modello**, il **Costo stimato** (una stima, vedi [Crediti e costi](10-crediti-e-costi.md)), il **Seed**, i **Parametri** e quando è stata generata;
- tutte le immagini in griglia; cliccando su una si apre ingrandita;
- su ogni immagine: la stella dei preferiti, **Elimina**, **Anima in un video** e **Usa come sorgente (img2img)**;
- **Elimina generazione** per cancellarla per intero.

Se è fallita, vedi il messaggio d'errore al posto delle immagini.

## Riusare una generazione

- **Usa configurazione** apre il form già compilato con modello, LoRA, parametri, prompt e seed di quella generazione, per rifarla o variarla. Un file caricato o una maschera non si possono riproporre (un campo file non si compila da programma): vanno ricaricati. Se il modello non è più disponibile, la pagina si apre normale con un messaggio.
- **Usa prompt** e **Usa seed** copiano solo il prompt o solo il seed nel form che apri dopo. Il seed è per file: nel dettaglio ogni immagine ha il suo. Quando una generazione ha prodotto più immagini insieme e i log non riportano un seed per ciascuna, la prima mostra il seed del gruppo con l'etichetta **(batch)** e le altre non ne mostrano: quel seed riproduce con certezza solo la prima.

## Quando una generazione resta in sospeso

Se l'app si riavvia mentre una generazione è in corso, al riavvio riprende a seguirla; una generazione che supera il tempo massimo (cinque minuti per le immagini, quindici per i video) viene chiusa come fallita e la richiesta annullata, così non resta mai in uno stato indefinito.
