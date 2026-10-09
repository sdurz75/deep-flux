# Galleria e archivio

Ogni generazione, dalla chat o dal form, è una riga dell'archivio. Questa pagina descrive come si sfoglia, si ordina con tag e preferiti e si pulisce.

## La Galleria

[Galleria](/gallery) mostra le immagini **riuscite**; scorrendo verso il fondo se ne caricano altre da sole. Si aggiorna da sola quando ne finisce una nuova, senza ricaricare. Per aggiornarla a mano c'è l'icona **Aggiorna** accanto al titolo; da telefono basta anche tirare la pagina verso il basso quando sei in cima e rilasciare. Restano scheda e tag scelti. Tre schede:

- **Tutte**: una scheda per ogni **file** (immagine o video); una generazione con più immagini ne produce più, una accanto all'altra.
- **Preferiti**: le schede dei file con la stella.
- **Importate**: le immagini che hai portato tu (vedi [Importare immagini](08-importare-immagini.md)); se non ce ne sono, un link ti porta alla pagina di importazione.

Sulla miniatura trovi la stella (**Aggiungi ai preferiti** / **Rimuovi dai preferiti**), l'icona **Anima in un video**, l'icona **Usa come sorgente (img2img)** per ripartire da quell'immagine, **Scarica**, e **Vedi dettaglio**, che apre `/generations/{id}` con tutti i dati (vedi [Genera immagini](03-genera-immagini.md#il-dettaglio-di-una-generazione)).

### Preferiti

La stella si mette per file: una generazione con quattro immagini può averne una sola preferita. Il filtro **Preferiti** le raccoglie tutte.

### Cancellare

Nelle schede **Tutte** e **Importate** puoi selezionare più file con le caselle (**Seleziona questa immagine**) ed **Elimina selezionate**; l'app chiede conferma. Cancellare l'ultimo file di una generazione elimina anche la generazione. Nella scheda **Preferiti** non c'è la cancellazione in blocco.

## Tag

I **tag** sono etichette tue, per ritrovare le cose. Si mettono su:

- una **generazione** intera;
- un **singolo file** di una generazione;
- una **conversazione** di Deep Chat;
- un'immagine **importata** (questi tag sono tuoi e restano distinti da quelli che l'analisi automatica scrive da sola).

Nell'editor dei tag, che trovi nel dettaglio e nella colonna delle conversazioni, i tag sono etichette con una «x» per toglierli; scrivi il nuovo nel campo **+ tag** e premi Invio. Il campo suggerisce i tag già usati. I tag vengono normalizzati: minuscoli, spazi collassati, niente virgole né virgolette, al massimo quaranta caratteri e venti tag per elemento.

Per usarli:

- in [/gallery](/gallery) il campo **Tag** filtra le schede (vale in entrambe);
- in [/search](/search) c'è lo stesso campo, vedi [Ricerca e note](09-ricerca-e-note.md);
- l'assistente di [Deep Chat](02-deep-chat.md) può cercare e mettere tag su tua richiesta.

I tag sono sempre in sola lettura nelle schede della galleria e nei risultati della ricerca.

## Le generazioni nella ricerca

La voce **Generazioni** del menu **Gestione** (e il link **Tutte le generazioni** in [Galleria](/gallery), e i riquadri della home) apre la [Ricerca](/search) già filtrata sul tipo **Generazioni**: lì le ritrovi per significato o per data, con i filtri di media, preferiti e tag. Nella ricerca compaiono solo le generazioni **riuscite**: una generazione fallita o ancora in corso non ha un elenco, la vedi dal suo dettaglio e dagli [eventi](/system/events).

Per cancellarne più d'una usa la selezione multipla della [Galleria](/gallery); dal dettaglio di una generazione puoi eliminarla singolarmente.

## La galleria di una conversazione

In Deep Chat, sotto la chat, il pannello **Galleria della conversazione** mostra tutti i file generati in quella conversazione, una scheda per file. La selezione e la cancellazione lì sono per file: una generazione che perde tutti i file viene eliminata.
