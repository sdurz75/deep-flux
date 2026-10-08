# LoRA e segreti

Un **LoRA** è un piccolo modello aggiuntivo che insegna a FLUX uno stile o un soggetto. In deep-flux si usano in due modi: **caricandoli al volo** nel form di flux-dev-lora, oppure con il LoRA già addestrato su Replicate, flux-lora-ff3 e i suoi simili. Un LoRA tuo si addestra da [Addestrare un LoRA](12-addestrare-lora.md): al termine nasce da solo un LoRA salvato che punta al suo modello.

## flux-dev-lora: LoRA al volo

Scegliendo **flux-dev-lora** nel form [Genera immagine](/generations/new) compaiono i campi dei LoRA:

- **LoRA** e **Intensità LoRA**: la sorgente del LoRA e quanto pesa. Vuoti, il modello funziona come FLUX dev normale.
- **LoRA aggiuntivo** e **Intensità LoRA aggiuntivo**: un secondo LoRA da usare insieme al primo.
- **Token HuggingFace** e **Token CivitAI**: servono solo per i LoRA privati (vedi sotto).

La sorgente di un LoRA può essere, a scelta:

| Dove sta | Come si scrive |
|---|---|
| Replicate | owner/nome, oppure owner/nome/versione |
| HuggingFace | huggingface.co/owner/nome, con /file.safetensors se il repository ne contiene più di uno |
| CivitAI | civitai.com/models/ID |
| Altrove | l'indirizzo diretto di un file .safetensors |

Con un'immagine di partenza facoltativa flux-dev-lora lavora anche in img2img: vedi [Modifica e inpainting](04-modifica-e-inpainting.md).

## flux-lora-ff3 e gli altri LoRA addestrati su Replicate

Questi modelli hanno il LoRA già incorporato e lo stesso form per tutti. Oltre al prompt hanno un secondo LoRA facoltativo (**LoRA aggiuntivo**, solo sorgenti pubbliche, senza token), un'immagine di partenza facoltativa, la **Forza del prompt (img2img)** e un editor della maschera per ritoccare una zona.

## I LoRA salvati

La pagina [LoRA](/loras), nel menu **Gestione**, è una rubrica dei LoRA che usi spesso. Con **Nuovo LoRA** ne salvi uno con:

- **Nome** (unico);
- **Sorgente**, nelle forme della tabella sopra (sotto il campo c'è un promemoria);
- **Intensità predefinita**;
- **Trigger words** (facoltative): le parole da mettere nel prompt per attivare il LoRA;
- **Nota** (facoltativa).

I LoRA salvati servono solo per comodità. Nel form, sopra i campi dei LoRA, la select **LoRA predefinito** ne compila sorgente e intensità; i campi restano modificabili, e **— testo libero —** non tocca nulla. Sotto la select compaiono le **Trigger words** con il pulsante **Aggiungi al prompt** (solo nel form diretto, non nel pannello della chat). Cancellare o modificare un LoRA salvato non cambia le generazioni già fatte.

### Un LoRA di Replicate diventa anche un modello

Se la sorgente è nella forma owner/nome (un LoRA addestrato su Replicate, senza indirizzi di siti né file), salvarlo lo aggiunge anche all'elenco dei modelli, come flux-lora-ff3: da quel momento puoi sceglierlo nel form, in Deep Chat e ovunque compaiono i modelli. L'app legge da Replicate l'ultima versione e ne blocca l'hash. Se il modello non esiste o non è un LoRA di FLUX, il salvataggio del LoRA riesce comunque e il modello semplicemente non viene aggiunto. Cancellare il LoRA salvato non toglie il modello.

## Segreti (token API)

Per scaricare LoRA privati servono i token di HuggingFace o CivitAI. Si gestiscono nella pagina [Segreti](/secrets), nel menu **Gestione**: **Nuovo segreto** chiede:

- **Tipo** (CivitAI o HuggingFace; ci sono anche Token API, Password e Segreto generico per altri usi);
- **Nome**, unico per tipo;
- **Valore** (il token);
- **Scadenza** (facoltativa): nessuno dei due servizi la comunica, la inserisci tu.

Cose da sapere:

- Il token **non si digita nel form di generazione**: lo scegli per nome dalla select (**— nessuno —** se non serve). Le voci mostrano il nome e le ultime quattro cifre (**nome ••••1234**), poi **(scade il …)** o **(scaduto)**, e il link **Gestisci i token** porta qui.
- Per un LoRA salvato in [LoRA](/loras) puoi indicare un **Token predefinito**: scegliendo quel LoRA nel form di flux-dev-lora la select del token giusto (HuggingFace o CivitAI) si compila da sola, e resta modificabile. Se il token viene cancellato, il LoRA resta ma senza token predefinito.
- Dopo il salvataggio il token non si vede più: resta visibile solo l'ultima parte, per riconoscerlo.
- I token sono salvati **cifrati**. Serve la chiave di cifratura nella configurazione del server (vedi [Dati, storage e sicurezza](../02-architettura/07-dati-storage-sicurezza.md)); senza, la pagina lo segnala e non si possono salvare token.
- Un token scaduto o cancellato blocca la generazione **prima** di chiamare Replicate: niente costi per un errore evitabile.
- A ridosso della scadenza (quindici giorni prima) arriva un avviso nella campanella, vedi [Eventi e problemi](11-eventi-e-problemi.md).
