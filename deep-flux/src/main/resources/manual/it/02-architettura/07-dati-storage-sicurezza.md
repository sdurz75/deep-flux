# Dati, storage e sicurezza

Dove stanno i dati, come cambiano gli schemi, dove finiscono i file e come si proteggono i segreti.

## Il database

PostgreSQL con pgvector, un'unica istanza per dati e vettori. Per lo sviluppo, `compose.yaml` ne avvia una con i dati in `./data/postgres` (fuori da git); le credenziali sono nel file `.env`.

### Le migrazioni

Lo schema lo gestisce **Flyway**; Hibernate ha `ddl-auto: validate` e controlla solo che lo schema corrisponda alle entity (altrimenti l'app non parte). Ogni modifica alla persistenza richiede una **nuova migrazione**.

- Due cartelle, `db/migration/core` per le tabelle del core e `db/migration/app` per quelle dell'app, fuse in una sola sequenza.
- I nomi sono a **timestamp**: `V2026_10_05_1000__descrizione.sql`. Un numero progressivo per cartella non funzionerebbe: i timestamp si ordinano senza coordinarsi. Una migrazione nuova ha sempre un timestamp successivo a tutte le esistenti, e un file già eseguito non si modifica mai (il controllo del checksum farebbe fallire l'avvio).
- **Nessuna chiave esterna dal core verso l'app**. Le colonne che collegano due funzioni dell'app sono semplici numeri; la chiave esterna esiste solo nel verso delle dipendenze.
- Regole del dialetto: identificatori in minuscolo e non quotati, testo lungo come `text`, `timestamptz`, `bytea`, e gli enum Java come `varchar`.
- In sviluppo si riparte da zero con `docker compose down` e cancellando `data/postgres`.

## I file binari

Immagini, video e upload passano **tutti** da `IImageStorageService`: nessun accesso diretto al filesystem o a WebDAV altrove e nessun gestore statico di risorse. Le immagini escono dall'app in un solo punto, `GET /images/{file}` (con supporto agli intervalli di byte per il video e agli ETag).

- **Nomi**: ogni file nuovo si chiama come lo SHA-256 di 32 byte casuali, in esadecimale, più l'estensione. Mai derivato da identificativo, indirizzo o nome originale: niente collisioni e nessuna informazione sul contenuto. Non è un hash del contenuto, quindi nessuna deduplicazione.
- **Percorso fisico**: il nome nel database e nell'URL è piatto, ma sul backend il file sta in `ab/cd/<nome>`, dove `abcd` sono i primi due byte dello SHA-256 del nome.
- **Backend**: `storage.type` sceglie `local` (predefinito, `./data/images`) o `webdav`.
- **Validazione degli upload**: tipo controllato sui byte iniziali (PNG, JPEG, WebP) e dimensione massima di dieci megabyte.

### WebDAV cifrato

Su WebDAV i contenuti sono **sempre cifrati** (AES-256-GCM a blocchi da 64 KiB, autenticato, con accesso a intervalli senza decifrare tutto) con la chiave `storage.webdav.encryption-key`, che arriva dalla variabile d'ambiente `HX_STORAGE_WEBDAV_ENCRYPTION_KEY`: 32 byte in base64, generabili con `openssl rand -base64 32`. Solo i contenuti sono cifrati, i nomi no. **Persa la chiave i file sono irrecuperabili**: conservane una copia. Una cache locale cifrata (2 GB di default, `0` per spegnerla) evita di riscaricare i file letti spesso.

Passare da locale a WebDAV non sposta i file esistenti da solo: esiste una migrazione una tantum, attivabile da configurazione, che copia i file locali, salta quelli già presenti e non ferma tutto per un file che fallisce.

## Segreti

I segreti (token API come CivitAI e HuggingFace, password, segreti dei moduli) sono un'unica entità con un **tipo** e si salvano cifrati con **la stessa chiave** e lo stesso algoritmo dei file WebDAV: nessun segreto nuovo da gestire. Con `storage.type=local` la chiave non è obbligatoria all'avvio, ma senza di essa la pagina Segreti non permette di salvare nulla. Dopo il salvataggio il valore non si vede più (restano gli ultimi quattro caratteri) e non finisce mai in log, eventi, toast o modello della pagina. Il core non sa nulla dei servizi: il core offre i tipi `API_TOKEN`, `PASSWORD` e `GENERIC`, l'app e i moduli ne aggiungono altri implementando `ISecretTypeCatalog` (più cataloghi si sommano). Un tipo «gestito» appartiene a un modulo: si vede in elenco ma si cambia dalle impostazioni del modulo. Un controllo periodico avvisa dei segreti in scadenza o scaduti.

## Errori delle chiamate remote

Ogni servizio esterno (Replicate, OpenRouter, SearXNG, WebDAV) passa da un'unica eccezione, `RemoteServiceException`, che porta la fonte e un **tipo**:

| Tipo | Significato |
|---|---|
| TRANSIENT | rete, timeout, 408, 429, 5xx: ritentabile |
| PERMANENT | 4xx, 507, risposta illeggibile |
| CONFIGURATION | credenziale o token mancante |
| REJECTED | rifiuto applicativo atteso (validazione, «non trovato»): non si registra |

`RemoteCaller` traduce qualunque eccezione e ritenta solo i transitori; per le operazioni **non idempotenti o a pagamento** la politica è sempre esplicita e senza retry. Gli errori veri finiscono nel registro eventi di sistema (mai un `catch` che li ingoia), con un avviso in tutte le schede aperte. Un nuovo servizio remoto richiede: un valore nella sorgente degli eventi con la sua etichetta in entrambe le lingue, la sua eccezione e un client che implementa la porta out del sottosistema.

Il resto del funzionamento operativo è in [Operatività](08-operativita.md).
