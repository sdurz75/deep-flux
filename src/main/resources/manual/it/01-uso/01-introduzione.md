# Introduzione

deep-flux serve a tre cose: **generare immagini e video** (anche con l'aiuto di un assistente in chat), **conservarli in un archivio** che si può sfogliare e ricercare, e **ritrovare le conversazioni** fatte con l'assistente. È pensato per una sola persona: non ci sono account, ogni cosa che vedi è tua.

## Cosa si può fare

- Conversare con l'assistente in [Deep Chat](02-deep-chat.md): descrivi l'immagine che vuoi, l'assistente può cercare sul web per informarsi e generarla. Il risultato compare nella conversazione.
- Generare direttamente, senza chatbot, dal form di [Genera immagine](03-genera-immagini.md) o di [Genera video](06-video.md).
- Ritoccare un'immagine esistente, cambiandola con un'istruzione o ridipingendone solo una zona: vedi [Modifica e inpainting](04-modifica-e-inpainting.md).
- Usare LoRA (stili e soggetti addestrati) con le [LoRA e i token](05-lora-e-token.md).
- Sfogliare, taggare e cancellare nella [Galleria e archivio](07-galleria-e-archivio.md).
- Portare nell'archivio immagini tue con l'[importazione](08-importare-immagini.md).
- Ritrovare tutto per significato con la [Ricerca e note](09-ricerca-e-note.md).

## Il menu

La barra in alto ha queste voci:

| Voce | Dove porta |
|---|---|
| Deep Flux | la chat con l'assistente, [/deep-chat](/deep-chat) |
| Crea | **Genera immagine**, **Genera video**, **Importa immagini** |
| Galleria | le immagini riuscite, [/gallery](/gallery) |
| Ricerca | la ricerca per significato, [/search](/search) |
| Gestione | **Generazioni**, **LoRA**, **Eventi**, **Token** |
| Manuale | questo manuale, [/manual](/manual) |

Sotto una certa larghezza dello schermo il menu si apre dal pulsante con le tre lineette. La voce **Ricerca** compare solo se la ricerca semantica è attiva.

## La barra in basso

In fondo a ogni pagina c'è una barra sempre visibile. A sinistra mostra l'ora e il codice della versione in esecuzione, utile per sapere quale build sta girando. A destra mostra il **credito residuo** dei servizi a pagamento (vedi [Crediti e costi](10-crediti-e-costi.md)) e il selettore del tema.

## Tema chiaro e scuro

Il selettore del tema ha tre posizioni: chiaro, scuro e **Auto**, che segue l'impostazione del tuo sistema. La scelta resta nel browser.

## La campanella

In alto a destra la campanella si accende quando c'è qualcosa da sapere: un errore di un servizio esterno, un avviso di scadenza di un token. Cliccandola vedi gli ultimi eventi; la pagina completa è descritta in [Eventi e problemi](11-eventi-e-problemi.md).

## Costi

Generare costa denaro vero su Replicate, ogni volta. L'app lo sa e ti protegge:

- dalla chat, l'assistente genera solo dopo che hai confermato il prompt esatto, al massimo tre generazioni per turno;
- il dettaglio di ogni generazione mostra il **costo stimato**;
- un pulsante **Interrompi** ferma una generazione in corso.

Tutto il resto (sfogliare, cercare, taggare, chiacchierare) non spende su Replicate; la chat e le analisi usano i token di OpenRouter, che costano molto meno.

## Se qualcosa non va

Controlla [Eventi e problemi](11-eventi-e-problemi.md): elenca i messaggi più comuni e cosa fare. Puoi anche chiedere all'assistente: conosce questo manuale e risponde alle domande sull'uso dell'app.
