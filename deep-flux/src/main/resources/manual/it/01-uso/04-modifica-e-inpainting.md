# Modifica e inpainting

Oltre a generare da zero, si può **partire da un'immagine**. Non c'è una pagina a parte: nel form [Genera immagine](/generations/new) la domanda è «parto da un'immagine o da zero?», e alcuni modelli sono fatti apposta per partire da un'immagine.

## Tre modi di partire da un'immagine

| Cosa vuoi | Modello | Cosa serve |
|---|---|---|
| Cambiare un'immagine con un'istruzione («togli il cappello») | flux-kontext-dev | un'immagine e un prompt che è un'istruzione |
| Ridipingere solo una zona | flux-fill-dev o flux-fill-pro | un'immagine e una maschera |
| Variare un'immagine mantenendone l'impostazione | flux-lora-ff3 o flux-dev-lora | un'immagine (facoltativa) e la **Forza del prompt** |

I modelli del primo e del secondo tipo sono segnati **richiede un'immagine** nel campo **Modello**: senza sorgente non hanno senso, e se provi a generare l'app lo dice prima di spendere.

## Scegliere l'immagine di partenza

- Caricala dal disco con il campo **Immagine di partenza** (o **Immagine da modificare**): PNG, JPEG o WebP, fino a dieci megabyte.
- Oppure scegline una dell'archivio con **Scegli dall'archivio**: si apre un elenco delle tue immagini, generate e importate; **Usa un file caricato** torna al caricamento.
- Oppure parti da una miniatura: su ogni immagine, in [/gallery](/gallery) e nel dettaglio, l'icona **Usa come sorgente (img2img)** apre il form con quell'immagine già scelta.

Se hai caricato un file e hai anche scelto una sorgente dall'archivio, vale il file caricato.

## Kontext: modificare con un'istruzione

Con flux-kontext-dev il prompt non descrive l'immagine finale ma **cosa cambiare**: «aggiungi degli occhiali», «rendila una scena di notte». Il risultato conserva dimensioni e composizione dell'originale (l'opzione **Come l'immagine originale**). **Migliora il prompt con l'AI** guarda l'immagine e riscrive la tua bozza come istruzione (serve una bozza).

## Inpainting: ridipingere una zona

Con flux-fill-dev e flux-fill-pro devi fornire l'immagine **e una maschera**: la zona che il modello ridisegna. flux-fill-dev accetta anche un LoRA; flux-fill-pro è la qualità massima, senza LoRA, e ha un costo fisso per immagine.

### L'editor della maschera

**Disegna maschera** apre l'editor (**Maschera di inpainting**). Prima scegli l'immagine di partenza, altrimenti l'editor lo ricorda.

- **Pennello** ed **Ellisse** aggiungono alla maschera, **Gomma** toglie.
- **Dimensione** è la grandezza del pennello; **Sfumatura** ammorbidisce i bordi della zona, per evitare cuciture visibili.
- **Annulla** torna indietro di un passo, **Azzera** svuota la maschera, **Applica** la conferma e **Chiudi** esce.
- Sopra gli strumenti vedi la dimensione dell'immagine e, se è stata ridotta, quella della maschera.

Dopo **Applica** il form mostra **Maschera impostata** e due pulsanti: **Modifica maschera** e **Rimuovi**. Nell'anteprima la zona della maschera si vede colorata sopra l'immagine originale.

### Consigli per un buon risultato

- Dipingi un po' **oltre il contorno** di ciò che vuoi cambiare (per un volto: anche mento e attaccatura dei capelli).
- Nel prompt descrivi **solo ciò che deve comparire nella zona dipinta**, in poche parole: non descrivere tutta la scena. Se usi un LoRA, includi le sue trigger words.
- **Migliora il prompt con l'AI** per l'inpainting segue queste regole: guarda l'immagine e scrive una descrizione breve della sola zona dipinta. Non vede la maschera, la deduce dalla tua bozza.

## Varianti img2img con i LoRA

Con flux-lora-ff3 e flux-dev-lora l'immagine di partenza è facoltativa. Se la dai, la **Forza del prompt (img2img)** decide quanto cambiare: un valore basso modifica poco (descrivi solo ciò che cambia), uno alto riscrive quasi tutto (descrivi l'immagine finale). Con flux-lora-ff3, quando c'è una sorgente la dimensione la decidono i **Megapixel**. flux-lora-ff3 accetta anche una maschera, ma ridipinge con meno pulizia di flux-fill-dev: per bordi puliti scegli quest'ultimo.

## Poi?

Un'immagine modificata è un'immagine come le altre: si può modificare di nuovo, animare in un video (vedi [Genera video](06-video.md)) o usare come sorgente. Per usare i LoRA e i loro token vedi [LoRA e token](05-lora-e-segreti.md).
