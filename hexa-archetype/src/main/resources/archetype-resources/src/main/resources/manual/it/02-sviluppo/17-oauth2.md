# Accesso con OAuth2 (hexa-oauth2)

Questa pagina riguarda le app generate con `-DuseOauth2=true` (dipendenza `hexa-oauth2`). L'app chiede di accedere con un provider OAuth2/OIDC (Google, Microsoft, Keycloak, Authentik...) e lascia entrare **solo gli utenti in una lista**: essere autenticati da Google non basta. Non serve codice.

## Le tre cose che contano

- **Spento di default e invisibile.** A cancello spento nessuna richiesta cambia risposta: l'app è aperta come senza la libreria. Si accende da `/oauth2` (menu «Gestione», voce «Accesso») o con `HX_OAUTH2_ENABLED=true`.
- **Fallisce chiuso.** Acceso senza provider o senza utenti ammessi, nessuno entra. Per questo da `/oauth2` si può accendere solo dopo aver configurato un provider, almeno un ammesso e fatto un **accesso di prova** riuscito («Prova accesso»).
- **La lista decide.** Un account autenticato dal provider ma non in lista non ottiene mai una sessione.

## Essere operativi con poche variabili

Nel `.env` (vedi `.env.example`, sezione OAuth2) bastano:

| Variabile | Significato |
| --- | --- |
| `HX_OAUTH2_PROVIDER` | `google` (l'issuer è implicito) oppure vuoto per un OIDC generico |
| `HX_OAUTH2_ISSUER_URI` | l'issuer OIDC (non serve per `google`); per Microsoft quello del tenant |
| `HX_OAUTH2_CLIENT_ID`, `HX_OAUTH2_CLIENT_SECRET` | le credenziali del client registrato presso il provider |
| `HX_OAUTH2_ALLOWED_EMAILS`, `HX_OAUTH2_ALLOWED_DOMAINS` | chi può entrare, elenchi separati da virgole |
| `HX_OAUTH2_ENABLED` | `true` accende il cancello all'avvio |
| `HX_OAUTH2_RESET` | `true` lo spegne all'avvio (recupero) |

Il provider d'ambiente compare in `/oauth2` in sola lettura e il suo segreto non viene mai salvato né scritto nei log. Gli elenchi d'ambiente si **sommano** a quelli salvati da `/oauth2`: valgono anche come recupero se la lista salvata è sbagliata. Le stesse chiavi esistono come property `app.oauth2.*` (`enabled`, `reset`, `provider`, `issuer-uri`, `client-id`, `client-secret`, `title`, `allowed-emails`, `allowed-domains`) e prevalgono sulle variabili.

L'indirizzo di ritorno da registrare presso il provider è `<indirizzo dell'app>/login/oauth2/code/<identificativo>`, dove l'identificativo è `google` o `sso` per il provider d'ambiente; `/oauth2` mostra quello esatto per ogni provider. Dietro un reverse proxy servono `server.forward-headers-strategy=framework` e l'`X-Forwarded-Prefix` per un sottopercorso, altrimenti l'indirizzo calcolato non è quello pubblico.

## Altri provider, da UI

`/oauth2` permette di aggiungere provider: identificativo, nome, issuer, client ID e il **segreto**, che non si scrive lì ma si salva in `/tokens` con il servizio «OAuth2» e si sceglie da una lista (cifrato con la chiave dell'app, come ogni token). La configurazione OIDC si legge da `<issuer>/.well-known/openid-configuration` (con il `RestClient.Builder` dell'app) e si tiene in cache qualche minuto.

## Chi può entrare

- **Email esatta**: confrontata dopo aver tolto gli spazi e messa in minuscolo, e solo se il provider dichiara `email_verified`. Al primo accesso si lega all'identità del provider (`issuer` + `sub`): da quel momento solo quell'identità vale per quell'email.
- **Dominio**: vale solo se **uguale** alla parte dopo l'ultima chiocciola (`example.com` non fa entrare `evilexample.com` né `sub.example.com`).

Con il cancello acceso non si può togliere l'ultima voce utile né l'ultimo provider.

## Dove si applica e che cosa lascia passare

La lista si applica **dentro** l'autenticazione (`AllowlistOidcUserService`), prima che la sessione sia salvata, usando solo i claim dell'id token (firma, issuer, audience e scadenza li verifica Spring Security). Con il cancello acceso passano senza accesso gli asset (`/js/`, `/css/`), la pagina di accesso, i path dei flussi OIDC e quelli dichiarati dalle librerie con `ILockExemptPaths` (con `hexa-pwa`: manifest, service worker, pagina offline, icone: il browser li chiede senza cookie). Il resto riceve: un redirect alla pagina di accesso (navigazione), un 401 con `HX-Redirect` (htmx, tranne se già sulla pagina di accesso) o un 401 secco (SSE, immagini, fetch).

## Con il blocco con PIN

L'ordine è: prima l'accesso OAuth2, poi, se attivo, il PIN ([Blocco con PIN](16-blocco-con-pin.md)). La pagina di sblocco non è esente dall'accesso.

## Sicurezza: che cosa è diverso da un'app con Spring Security "normale"

- La catena di filtri è di `hexa-oauth2`: intestazioni di default e CSRF di Spring Security sono spenti (a cancello spento non devono cambiare nulla). A cancello acceso un `SameOriginFilter` rifiuta con 403 le richieste che modificano dichiarate da un altro sito (`Sec-Fetch-Site`, o `Origin` diverso dall'host); così htmx, i `fetch` degli script e gli upload funzionano senza token.
- Il client usa authorization code con **PKCE** anche se confidenziale.
- Non si introduce Spring Security a parte: una seconda catena si scontrerebbe con questa.

## Recupero

Chiusi fuori (provider cambiato, segreto scaduto, lista sbagliata): `HX_OAUTH2_RESET=true` nel `.env` e riavvio; il cancello si spegne e l'evento resta negli eventi di sistema. Poi togliere la riga. Se `HX_OAUTH2_ENABLED=true` è impostata senza provider o ammessi, all'avvio compare un avviso.

## Testare

Mai contro un provider vero: `hexa-oauth2` prova il flusso intero con un provider OIDC finto in-process (discovery, JWKS, endpoint dei token con una chiave RSA generata). Il login vero con Google/Microsoft e il comportamento dietro un reverse proxy o nella PWA installata si provano a mano.
