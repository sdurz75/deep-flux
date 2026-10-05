# Addestrare un LoRA

Con [Addestra un LoRA](/trainings), nel menu **Crea**, puoi addestrare un tuo LoRA (un soggetto o uno stile) a partire da un gruppo di immagini. L'addestramento gira su Replicate (`replicate/fast-flux-trainer`) ed è **a pagamento**: il costo dipende dai passi e dalla durata. Alla fine il LoRA compare tra i [LoRA salvati](05-lora-e-token.md) e tra i modelli, pronto da usare.

## Come funziona, in breve

1. Crei un **dataset**: un nome, una trigger word e le immagini.
2. Ritagli le immagini che lo richiedono e controlli le **didascalie**, scritte in automatico e correggibili a mano.
3. Con **Avvia il training** parte l'addestramento. Ogni lancio congela una copia del dataset: quello che modifichi dopo non cambia il training già partito.
4. Segui il training nello **Storico dei training**. Quando finisce, il LoRA è già un preset in [LoRA](/loras) e un modello utilizzabile.

La pagina ha due schede: **Dataset** (le tue bozze) e **Storico dei training**.

## Il dataset

**Nuovo dataset** chiede:

- **Nome**;
- **Trigger word**: una parola che non esiste, come TOK. Il LoRA la associa a tutte le immagini e tu la scrivi nei prompt per attivarlo. Una sola parola, senza spazi né virgole;
- **Che cosa insegna**: un **Soggetto** (una persona, un animale, un oggetto) oppure uno **Stile**;
- **Nota** (facoltativa).

Il dataset è una **bozza che si salva da sola sul server**: puoi chiudere la pagina, riprenderla il giorno dopo, cambiare qualcosa e lanciare un nuovo training. Nell'elenco, ogni dataset ha **Clona** (una copia indipendente, con le immagini e le didascalie) ed **Elimina**. Eliminare un dataset non tocca i training già lanciati.

## Le immagini

Trascina le immagini nella zona di caricamento, incollale o scegli dal disco, poi **Aggiungi al dataset**. Sono ammessi PNG, JPEG e WebP, fino a 10 MB ciascuno; un file rifiutato non ferma gli altri, e l'esito è indicato per ogni file. Un dataset può contenere al massimo 25 immagini.

Servono **almeno 4 immagini** per lanciare; sotto le 10 compare un avviso, perché il risultato di solito è scarso. Per un soggetto conviene variare posa, sfondo e luce.

### Il ritaglio

Il pulsante **Ritaglia** di ogni immagine apre un editor nel browser: trascina il riquadro per spostarlo, gli angoli per ridimensionarlo, oppure disegnane uno nuovo. Le **Proporzioni** sono **Libero** o fisse (1:1, 4:3, 3:4, 3:2, 2:3, 16:9, 9:16). **Applica il ritaglio** salva una versione ritagliata; l'originale resta sempre, e **Ripristina l'originale** lo rimette al posto del ritaglio. Il ritaglio parte dall'originale, non dal ritaglio precedente.

## Le didascalie

Ogni immagine ha una **Didascalia**, che dice al trainer che cosa c'è nella foto. All'aggiunta di un'immagine viene scritta in automatico da un modello di visione, in inglese, nominando la trigger word; mentre lavora la card mostra **Descrizione in corso...** e si aggiorna da sola.

- Puoi correggerla a mano: da quel momento è **scritta a mano** e non viene più sostituita. **Rigenera** la rifà in automatico, dopo una conferma se l'avevi scritta tu.
- Se il modello rifiuta un'immagine o non riesce, la card lo dice e puoi scriverla a mano o riprovare.
- Se ritagli un'immagine (o ripristini l'originale), la didascalia che non hai scritto tu viene rifatta, perché descriveva un'altra inquadratura; quella scritta a mano resta.
- Le azioni sull'intero dataset sono sotto **Didascalie**: **Rigenera le automatiche** (anche le mancanti e le non riuscite; le scritte a mano restano) e **Aggiungi la trigger word dove manca**.

Le didascalie automatiche costano token di OpenRouter, molto meno di un training. Una didascalia che non nomina la trigger word è segnalata: è solo un avviso, ma di solito conviene aggiungerla.

## Avviare il training

Sotto le immagini, il pannello **Avvia il training** raccoglie le impostazioni e controlla che tutto sia a posto. Le impostazioni si salvano con il dataset (**Salva le impostazioni**):

- **Nome del modello**: il nome di base del modello Replicate che nasce dal training; l'app ci aggiunge data e ora, così ogni training ha il proprio modello. Vuoto: il nome del dataset;
- **Passi di addestramento**: fra 100 e 4000, 1000 di partenza. Più passi, più tempo e più costo;
- **Seed** (facoltativo): vuoto = casuale;
- **Copia i pesi su HuggingFace**, acceso di partenza (vedi sotto).

Sotto **Prima di avviare** compaiono i motivi che impediscono il lancio (poche immagini, didascalie ancora in corso o mancanti, un token HuggingFace scaduto), sotto **Da considerare** gli avvisi. Quando è tutto pronto, **Avvia il training** chiede una conferma, perché è a pagamento e, una volta partito, annullarlo non restituisce ciò che è già stato calcolato. Il caricamento delle immagini su Replicate può richiedere un po': la pagina resta bloccata finché non ha finito.

Un solo training alla volta per ogni bozza: se ne è già in corso uno, l'avvio è bloccato finché non finisce o lo annulli.

### La copia su HuggingFace

Di partenza, oltre al modello su Replicate (quello che userai nell'app), il trainer carica una copia dei pesi in un tuo repo HuggingFace, **privato**. Serve un [token HuggingFace](05-lora-e-token.md) con permesso di scrittura, scelto per nome (si salvano in [Token](/tokens)); un token di sola lettura è rifiutato. L'app crea il repo prima del lancio. Il nome del repo, se non lo scegli, è quello del modello Replicate; se ne scegli uno che esiste già, i pesi lo sovrascrivono.

**Il token HuggingFace viene inviato a Replicate**, come segreto del trainer, perché possa caricare i pesi. L'app non lo salva con il training (la riga del training e il dataset congelato ricordano solo quale token hai scelto): resta soltanto, cifrato, in [Token](/tokens), e l'app lo rilegge da lì per controllare la copia a fine training. Se non vuoi che Replicate lo riceva, spegni **Copia i pesi su HuggingFace**: il modello su Replicate si crea comunque.

## Lo storico e il dettaglio di un training

La scheda **Storico dei training** elenca i lanci con stato, dataset, passi e data, e si aggiorna da sola. Il dettaglio di un training mostra impostazioni, **Log del trainer**, il dataset congelato (le immagini e le didascalie com'erano al lancio) e, se c'è, l'errore. Finché è in corso la pagina si aggiorna da sola.

- **Annulla il training** lo ferma su Replicate; quello che ha già calcolato viene comunque fatturato.
- **Riprendi da questo** crea una nuova bozza da una copia del dataset congelato: modifichi qualcosa e lanci un nuovo training.
- **Elimina** toglie il training e il suo dataset congelato (un training in corso viene prima annullato). Non toglie il **modello su Replicate**, il **repo HuggingFace** né il **preset**: sono tuoi e restano.
- Un training che non finisce entro due ore viene interrotto da solo.

## Il risultato

Quando il training finisce, in pochi istanti l'app prepara il risultato e il dettaglio lo mostra sotto **Risultato**:

- un **preset** in [LoRA](/loras), con il nome del dataset (se esiste già un LoRA con quel nome, ci si aggiunge la data), la trigger word e una nota che rimanda al training;
- il **modello** come LoRA utilizzabile, come flux-lora-ff3: compare nel form [Genera immagine](/generations/new) e in Deep Chat, senza bisogno di token;
- la verifica della **copia su HuggingFace**: l'app controlla che nel repo ci siano davvero i file dei pesi.

Se qualcosa non torna (il modello non è utilizzabile nell'app, nel repo non si trovano i pesi) compare un avviso nella campanella e in [Eventi](/system/events); il training resta comunque completato e i pesi esistono. Subito dopo la fine, Replicate e HuggingFace possono impiegare qualche minuto a mostrare il modello e i file: l'app riprova per un po' prima di dare l'avviso. Il costo del training non entra nella stima del saldo Replicate della barra in basso: vedi [Crediti e costi](10-crediti-e-costi.md).
