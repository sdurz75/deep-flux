# Deep Chat

[Deep Chat](/deep-chat) (nel menu: **Deep Flux**) è la chat con l'assistente. Descrivi quello che vuoi, in italiano o in inglese: l'assistente ti aiuta a scrivere il prompt, può cercare informazioni sul web e, quando glielo confermi, genera l'immagine.

## Le conversazioni

La colonna a sinistra elenca le conversazioni, la più recente in alto. Ogni conversazione è salvata sul server: puoi chiudere la pagina e riprenderla più tardi.

- **Nuova conversazione** ne apre una vuota.
- Cliccando una conversazione dell'elenco ne ricarichi la cronologia completa.
- Puoi rinominarla (**Rinomina conversazione**) o cancellarla; la cancellazione chiede conferma e non si annulla. Le immagini già generate restano nell'archivio.
- Una conversazione può avere dei tag, per ritrovarla (vedi [Tag](07-galleria-e-archivio.md#tag)).

Sotto la chat c'è il pannello **Galleria della conversazione**, con tutte le immagini e i video generati in quella conversazione.

## Le impostazioni di generazione

Nella colonna a sinistra, sotto l'elenco, il pannello **Impostazioni** contiene il modello e i parametri con cui l'assistente genera. Sono gli stessi campi del [form di generazione](03-genera-immagini.md), con un'eccezione: la chat propone solo i modelli che funzionano senza un'immagine di partenza, e solo modelli per immagini (non video).

- L'assistente genera **sempre** con il modello scelto qui: non può sceglierne un altro. Se vuoi un modello diverso, cambialo nel pannello.
- Il numero di immagini per generazione lo decide il pannello, non l'assistente.
- Le impostazioni sono salvate **per conversazione**: aprendo una conversazione ritrovi il modello e i parametri che aveva.
- **Reimposta ai default** riporta modello e parametri ai valori iniziali.

## Come si genera

1. Parli con l'assistente e raffinate insieme la descrizione.
2. L'assistente ti propone il **prompt esatto** che userà e chiede conferma. Non genera alla prima menzione: se cambi idea, aggiorna la proposta.
3. Dopo il tuo sì parte la generazione. Compare subito un riquadro segnaposto con **Generazione in corso…** e il pulsante **Interrompi**.
4. Quando l'immagine è pronta il segnaposto viene sostituito dal risultato, con il tasto per mostrarla o nasconderla, la stella dei preferiti e le altre azioni. L'esito arriva da solo, senza ricaricare la pagina.

Se una generazione fallisce, o la annulli, nella conversazione compare un messaggio con il motivo. Al massimo tre generazioni partono per ogni tua richiesta.

## Cosa sa fare l'assistente

- **Cercare sul web** per informarsi (se il servizio di ricerca è configurato).
- **Generare** un'immagine, come descritto sopra.
- **Leggere l'app**: elencare i modelli disponibili, i LoRA salvati, i tag in uso, il dettaglio di una generazione (prompt, modello, seed, costo, errori), gli eventi recenti di sistema.
- **Cercare nell'archivio** per significato o per filtri (tag, tipo, preferiti, date): immagini generate e importate, note, conversazioni passate.
- **Curare l'archivio**, ma solo su tua richiesta esplicita: mettere o togliere la stella a un file, aggiungere o togliere tag a una generazione o a un file, rinominare e taggare la conversazione corrente. Dice sempre cosa ha fatto.
- **Salvare una nota** nell'archivio, solo se glielo chiedi.
- **Dire il credito residuo** e quanto è costata la conversazione.
- **Guardare un'immagine** dell'archivio quando il prompt non basta a rispondere alla tua domanda (al massimo due per risposta, perché costa).
- **Spiegarti come si usa l'app**, consultando questo manuale e citando la pagina.

L'assistente **non può** cancellare, interrompere, animare né rigenerare nulla da solo, e non apre form né carica file.

## I bottoni delle azioni proposte

Quando glielo chiedi, l'assistente può **proporre** un'azione: sotto la sua risposta compare un bottone e decidi tu.

| Bottone | Cosa fa |
|---|---|
| Interrompi la generazione #12 | ferma una generazione in corso, dopo conferma |
| Elimina la generazione #12 | la cancella con tutti i suoi file, dopo conferma |
| Elimina il file … di #12 | cancella un solo file; se è l'ultimo, anche la generazione |
| Rigenera #12 (stesso seed, modello …) | apre il form di generazione già compilato con modello, LoRA, parametri, prompt e seed |
| Anima #12 (…) | apre il form video con quell'immagine come sorgente |
| Usa #12 (…) come sorgente | apre il form immagine con quell'immagine come sorgente (img2img, Kontext, inpainting) |

Aprire un form non avvia nessuna generazione a pagamento: parte solo quando premi **Genera** nel form. I bottoni non sono salvati: ricaricando la pagina l'assistente può riproporli.

## Link nelle risposte

Quando l'assistente cita una pagina dell'app (per esempio [/gallery](/gallery)) o una generazione (`#12`), diventa un link cliccabile.

## Cosa non fa la chat

Non genera video, non modifica immagini con una maschera e non carica file: per queste cose si usano i form, descritti in [Genera video](06-video.md) e [Modifica e inpainting](04-modifica-e-inpainting.md). L'assistente ti spiega la strada e, dove serve, propone il bottone che apre il form giusto.
