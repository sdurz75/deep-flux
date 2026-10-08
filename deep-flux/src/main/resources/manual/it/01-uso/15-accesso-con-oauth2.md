# Accesso con OAuth2

Puoi far entrare in deep-flux solo chi accede con un account Google (o Microsoft, o un altro provider OIDC) **e** è in una lista di persone ammesse. Essere riconosciuti da Google non basta: se la tua email non è in lista, non entri. È pensato per un'app raggiungibile da internet; se la usi solo a casa puoi lasciarlo spento.

## Come si accende

Apri [Accesso](/oauth2), nel menu «Gestione». Il cancello è spento finché non lo accendi e, finché è spento, l'app si usa come sempre. Per accenderlo servono tre cose, e la pagina ti dice quali mancano:

1. un **provider**: quello configurato dal server con le variabili `HX_OAUTH2_*`, oppure uno aggiunto qui (nome, issuer, client ID e il segreto, che salvi prima in [Token](/tokens) con il servizio «Segreto client OAuth2»);
2. almeno un **utente ammesso**;
3. un **accesso di prova** riuscito: clicca «Prova accesso» accanto al provider e accedi con un account della lista. Serve a non chiuderti fuori per un errore di configurazione.

Poi premi «Accendi il cancello». Da quel momento, aprendo l'app ti viene chiesto di accedere. Il pulsante «Esci» in questa pagina chiude la sessione.

## Chi è ammesso

Aggiungi **email** (una persona) o **domini** (tutta un'organizzazione, per esempio `example.com`). Un'email vale solo se il provider la dichiara verificata; un dominio vale solo se è identico a quello dell'email, quindi `example.com` non fa entrare `evilexample.com`. Al primo accesso di un'email esatta, quell'email si lega all'identità che l'ha usata: un altro account che dichiara la stessa email viene respinto. Le voci «da ambiente» arrivano dal server e non si possono modificare qui.

Con il cancello acceso non puoi togliere l'ultimo provider né l'ultima voce utile: ti chiuderesti fuori.

## Registrare il provider

Presso il provider (per esempio la console Google Cloud) devi registrare l'**indirizzo di ritorno** che trovi nell'elenco dei provider, nella forma `https://tuo-indirizzo/login/oauth2/code/google`. Dipende dall'indirizzo con cui apri l'app: se lo cambi, va registrato di nuovo.

## Con il blocco con PIN

Se usi anche il [Blocco con PIN](14-blocco-con-pin.md), prima entri con il provider e poi, dopo un periodo di inattività, serve il PIN. Sono indipendenti: puoi usarne uno, l'altro o entrambi.

## Se ti chiudi fuori

Se il provider non funziona più o la lista è sbagliata, chi gestisce il server può impostare `HX_OAUTH2_RESET=true` nel file `.env` e riavviare: il cancello si spegne e l'evento resta negli [Eventi](/system/events). Poi va tolta la riga, altrimenti ogni riavvio lo spegne. Anche `HX_OAUTH2_ALLOWED_EMAILS` nel `.env` aggiunge subito persone alla lista, senza entrare nell'app.
