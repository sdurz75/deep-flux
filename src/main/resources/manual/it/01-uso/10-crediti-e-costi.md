# Crediti e costi

Due servizi a pagamento fanno funzionare l'app: **Replicate** (genera le immagini e i video) e **OpenRouter** (la chat con l'assistente, il miglioramento dei prompt, l'analisi delle immagini importate). La barra in basso ti dice quanto resta.

## La barra in basso

A destra, accanto al selettore del tema, compaiono fino a due indicatori:

- **Replicate**, con il simbolo ~ davanti all'importo: è una **stima**, perché Replicate non comunica il saldo.
- **OpenRouter**: il saldo esatto, quando è configurato.

Si aggiornano da soli quando carichi la pagina, ogni cinque minuti e quando arriva una nuova immagine. Se un importo scende sotto due dollari, l'indicatore diventa giallo. Se il servizio non risponde, vedi **n/d**.

### Il saldo di Replicate

Poiché Replicate non espone il credito, è l'app a stimarlo: tu inserisci il saldo che leggi nella dashboard di Replicate, e l'app sottrae il costo stimato delle generazioni fatte dopo. Per inserirlo clicca l'indicatore (o **Imposta saldo Replicate** se non c'è ancora): si apre un piccolo modulo con **Saldo Replicate (USD)** e **Salva**. Non scende mai sotto zero. Conviene riallinearlo ogni tanto con il valore vero.

### Il saldo di OpenRouter

Per leggerlo serve una chiave speciale (una *management key*) configurata sul server, diversa dal token della chat; senza, l'indicatore di OpenRouter non compare. Il valore si ricarica al massimo ogni cinque minuti.

## Il costo di una generazione

Il dettaglio di ogni generazione mostra il **Costo stimato**, calcolato dalle metriche che Replicate restituisce a fine lavoro. È una stima: per alcuni modelli il prezzo è fisso per immagine, per altri dipende dal tempo di calcolo. Non c'è per le generazioni vecchie, per quelle fallite e per i modelli senza una regola di prezzo.

L'assistente conosce lo stesso dato: sa dirti quanto ha speso una conversazione fin qui, ma **non può prevedere** il costo di una generazione prima che parta (Replicate non lo permette).

## Come tenere bassa la spesa

- Prova i prompt con una sola immagine per volta.
- Usa le bozze dove esistono (per i video, **Bozza**).
- In chat, conferma il prompt solo quando sei soddisfatto: l'assistente non genera senza.
- Ricorda che un'immagine importata e la ricerca non costano su Replicate.
