# Storage, segreti e backup

Tre capability di hexa-core che un'app usa quasi sempre insieme.

## Binari

Ogni file servito dall'app passa da [`IImageStorageService`](https://git.trenolab.com/a.duca/hexa/src/branch/main/hexa-core/src/main/java/org/dual/hexa/core/storage/port/in/IImageStorageService.java): l'app non tocca mai direttamente filesystem o WebDAV. Il backend si sceglie con `storage.type` (`local` o `webdav`). I nomi dei file sono generati dalla libreria (hash di byte casuali più estensione), mai derivati dal nome originale; i file sono serviti da `/images/**` con Range ed ETag. Con WebDAV i contenuti sono sempre cifrati con la chiave `storage.webdav.encryption-key`: se si perde, i binari sono irrecuperabili. La porta lancia solo `StorageException` e riceve `UploadedFile`, non `MultipartFile`.

## Segreti

Token API, password e altri segreti si gestiscono da [/secrets](/secrets): sono un'unica entità con un **tipo**. Dopo il salvataggio resta visibile solo la parte finale; il valore non finisce mai in log, eventi o modelli. Sono cifrati con [`ISecretCipher`](https://git.trenolab.com/a.duca/hexa/src/branch/main/hexa-core/src/main/java/org/dual/hexa/core/secrets/port/in/ISecretCipher.java) (stessa chiave dei binari WebDAV). Il core ha i tipi `API_TOKEN`, `PASSWORD` e `GENERIC`; l'app e i moduli aggiungono i propri implementando `ISecretTypeCatalog` (vedi `ExampleSecretTypes`: più cataloghi si sommano). Per ottenere il chiaro serve [`ISecrets`](https://git.trenolab.com/a.duca/hexa/src/branch/main/hexa-core/src/main/java/org/dual/hexa/core/secrets/port/in/ISecrets.java)`#resolve`, da usare solo nel punto che chiama il servizio.

## Backup

`java -jar app.jar export <file>` e `import <file> [--replace]` salvano e ripristinano database e binari. Il database è copiato per intero; i binari sono quelli **referenziati** dal database, che l'app dichiara implementando [`IBlobReferences`](https://git.trenolab.com/a.duca/hexa/src/branch/main/hexa-core/src/main/java/org/dual/hexa/core/backup/port/in/IBlobReferences.java) (le coppie tabella e colonna con i nomi dei file; vedi `ExampleBlobReferences`). Un test dell'app fa fallire il build se una colonna `%filename%` non è dichiarata. L'archivio è cifrato con `HX_BACKUP_ENCRYPTION_KEY` (o la chiave WebDAV); senza chiave serve `--no-encrypt`.

## Riferimento API

[`IImageStorageService`](https://git.trenolab.com/a.duca/hexa/src/branch/main/hexa-core/src/main/java/org/dual/hexa/core/storage/port/in/IImageStorageService.java), [`ISecrets`](https://git.trenolab.com/a.duca/hexa/src/branch/main/hexa-core/src/main/java/org/dual/hexa/core/secrets/port/in/ISecrets.java), [`ISecretCipher`](https://git.trenolab.com/a.duca/hexa/src/branch/main/hexa-core/src/main/java/org/dual/hexa/core/secrets/port/in/ISecretCipher.java), [`IBlobReferences`](https://git.trenolab.com/a.duca/hexa/src/branch/main/hexa-core/src/main/java/org/dual/hexa/core/backup/port/in/IBlobReferences.java).
