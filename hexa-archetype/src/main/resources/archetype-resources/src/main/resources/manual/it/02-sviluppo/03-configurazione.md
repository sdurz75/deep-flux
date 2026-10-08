# Configurazione

La configurazione è divisa fra le librerie e l'app, con chiavi disgiunte.

## I file

`application.yml` è dell'app e importa `core.yml` (da hexa-core) e, con hexa-ai, `ai.yml` e `prompts.properties`. Un file importato **vince** sul file che lo importa: una chiave di `core.yml` o `ai.yml` non si cambia dall'`application.yml`. Quello che si può cambiare sta nell'app o nei profili `application-<profilo>.yml`, che invece vincono.

## Segreti e ambiente

`.env` sta nella radice del progetto (parte da lì anche `spring-boot:run`) e non va in git: parte da `.env.example`. Contiene le credenziali del database (`HX_DB_*`) e le chiavi. `core.yml` non ha un default per `HX_DB_NAME` e `HX_DB_USERNAME`: sono dell'app.

## Chiavi principali

- `app.layout.nav`: `sidebar` (menu laterale) o `top` (barra in alto).
- `storage.type`: `local` o `webdav` (vedi [Storage, segreti e backup](07-storage-segreti-backup.md)).
- `app.secrets.encryption-key`: chiave AES-256 per i segreti cifrati; con `storage.type=local` senza chiave non si può salvare un segreto.
- `app.push.client-events`: i nomi degli eventi SSE propri dell'app.
- `app.events`, `app.secrets.expiry-*`: parametri del registro eventi e della scadenza dei segreti.

## Messaggi

I testi visibili stanno nei bundle `messages(.en).properties`. Le librerie hanno i propri (`messages-core*`, `messages-ai*`) e le chiavi non si sovrappongono: una chiave nuova va nel bundle dell'app, in entrambe le lingue. Il default è l'italiano; la lingua segue `Accept-Language`.

## Riferimento API

Le classi di autoconfigurazione sono in [`org.dual.hexa.core.autoconfigure`](https://sdurz75.github.io/deep-flux/apidocs/org/dual/hexa/core/autoconfigure/package-summary.html); i punti di estensione in `port.in` sono elencati nell'[indice generale delle API](https://sdurz75.github.io/deep-flux/apidocs/index.html).
