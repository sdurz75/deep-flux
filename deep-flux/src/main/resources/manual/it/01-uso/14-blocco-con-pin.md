# Blocco con PIN

Puoi proteggere deep-flux con un PIN: dopo un periodo senza attività l'app si blocca e per riaprirla serve il PIN. È pensato per un dispositivo lasciato incustodito e per chi apre l'app da un altro browser. Non cifra i tuoi dati.

## Attivarlo

Apri [Sicurezza](/security), nel menu «Gestione». Scegli un PIN di 4-8 cifre, ripetilo e scegli dopo quanti minuti di inattività l'app si blocca (da 1 a 60). Finché non imposti un PIN l'app non si blocca mai.

## Come funziona

Conta solo quello che fai tu: muovere il mouse, scrivere, toccare lo schermo, scorrere. Le generazioni in corso e gli aggiornamenti automatici non tengono sveglia l'app, e proseguono anche mentre è bloccata. Quando si blocca, ogni pagina, le immagini e gli aggiornamenti in tempo reale richiedono il PIN; dopo lo sblocco torni alla pagina dove eri. Con più schede aperte si bloccano tutte insieme. Il pulsante «Blocca ora» in Sicurezza chiude subito la sessione.

## Cambiarlo o toglierlo

Per cambiare il PIN, il tempo di inattività o per disattivare il blocco serve il PIN attuale. Dopo tre errori i tentativi rallentano: si aspetta 5 secondi, poi 10, 20 e così via fino a 15 minuti.

## Se dimentichi il PIN

Non c'è un recupero dall'app. Chi gestisce il server può impostare `HX_LOCK_RESET=true` nel file `.env` e riavviare: il blocco si spegne e l'evento resta negli [Eventi](/system/events). Poi va tolta la riga, altrimenti ogni riavvio spegne il blocco.
