# Accesso con OAuth2

Puoi far entrare in deep-flux solo chi accede con un account Google (o Microsoft, o un altro provider OIDC) **e** è in una lista di persone ammesse. Essere riconosciuti da Google non basta: se la tua email non è in lista, non entri. È pensato per un'app raggiungibile da internet; se la usi solo a casa puoi lasciarlo spento.

## Come si accende

Apri [Impostazioni](/settings), nel menu «Gestione», sezione «Accesso OAuth2». Il cancello è spento finché non lo accendi e, finché è spento, l'app si usa come sempre. Per accenderlo servono tre cose, e la sezione ti dice quali mancano:

1. un **provider**: quello configurato dal server con le variabili `HX_OAUTH2_*`, oppure uno aggiunto qui (identificativo, nome, issuer, client ID e segreto del client: il segreto si salva cifrato, lo trovi in [Segreti](/secrets) ma si cambia solo da qui, e lasciarlo vuoto lo conserva);
2. almeno un **utente ammesso**;
3. un **accesso di prova** riuscito: clicca «Prova accesso» accanto al provider e accedi con un account della lista. Serve a non chiuderti fuori per un errore di configurazione.

Poi attiva «Cancello di accesso acceso» e salva. Da quel momento, aprendo l'app ti viene chiesto di accedere. Il pulsante «Esci» in questa sezione chiude la sessione.

## Chi è ammesso

Scrivi le **email** (una persona, una per riga) e i **domini** (tutta un'organizzazione, per esempio `example.com`). Un'email vale solo se il provider la dichiara verificata; un dominio vale solo se è identico a quello dell'email, quindi `example.com` non fa entrare `evilexample.com`. Al primo accesso di un'email esatta, quell'email si lega all'identità che l'ha usata: un altro account che dichiara la stessa email viene respinto. Le voci dal server (`HX_OAUTH2_ALLOWED_*`) si sommano alle tue e compaiono in sola lettura. Se cambi i provider, l'identità legata a un'email e gli accessi di prova si azzerano.

Con il cancello acceso non puoi togliere l'ultimo provider né l'ultima voce utile: ti chiuderesti fuori.

## Registrare il provider

Presso il provider (per esempio la console Google Cloud) devi registrare l'**indirizzo di ritorno** che trovi nell'elenco dei provider della sezione, nella forma `https://tuo-indirizzo/login/oauth2/code/google`. Dipende dall'indirizzo con cui apri l'app: se lo cambi, va registrato di nuovo.

## Durata della sessione

Nella sezione OAuth2 di `/settings` decidi quanto resti collegato: **Durata della sessione (giorni di inattività)** (default 30, ogni uso rinnova il periodo), **Durata massima dal primo accesso (giorni, 0 = nessun limite)** e **Ricorda l'accesso alla chiusura del browser o della PWA** (acceso di default; spento, chiudere il browser o la PWA ti scollega). I valori valgono subito, senza riavvio. I token del provider non contano: dopo l'accesso non vengono più usati. Le sessioni sopravvivono anche a un riavvio dell'app (cartella `data/sessions`).

## Con il blocco con PIN

Se usi anche il [Blocco con PIN](14-blocco-con-pin.md), prima entri con il provider e poi, dopo un periodo di inattività, serve il PIN. Sono indipendenti: puoi usarne uno, l'altro o entrambi.

## Se ti chiudi fuori

Se il provider non funziona più o la lista è sbagliata, chi gestisce il server può impostare `HX_OAUTH2_RESET=true` nel file `.env` e riavviare: il cancello si spegne e l'evento resta negli [Eventi](/system/events). Poi va tolta la riga, altrimenti ogni riavvio lo spegne. Anche `HX_OAUTH2_ALLOWED_EMAILS` nel `.env` aggiunge subito persone alla lista, senza entrare nell'app.
