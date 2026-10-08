# Storage, token e backup

Tre capability di hexa-core che un'app usa quasi sempre insieme.

## Binari

Ogni file servito dall'app passa da [`IImageStorageService`](https://sdurz75.github.io/deep-flux/apidocs/org/dual/hexa/core/storage/port/in/IImageStorageService.html): l'app non tocca mai direttamente filesystem o WebDAV. Il backend si sceglie con `storage.type` (`local` o `webdav`). I nomi dei file sono generati dalla libreria (hash di byte casuali più estensione), mai derivati dal nome originale; i file sono serviti da `/images/**` con Range ed ETag. Con WebDAV i contenuti sono sempre cifrati con la chiave `storage.webdav.encryption-key`: se si perde, i binari sono irrecuperabili. La porta lancia solo `StorageException` e riceve `UploadedFile`, non `MultipartFile`.

## Token API

I token si gestiscono da [/tokens](/tokens). Dopo il salvataggio resta visibile solo la parte finale; il segreto non finisce mai in log, eventi o modelli. Sono cifrati con [`ISecretCipher`](https://sdurz75.github.io/deep-flux/apidocs/org/dual/hexa/core/secrets/port/in/ISecretCipher.html) (stessa chiave dei binari WebDAV). L'app dice quali servizi esistono implementando `ITokenProviderCatalog` (vedi `ExampleTokenProviders`); per ottenere il chiaro serve [`IApiTokens`](https://sdurz75.github.io/deep-flux/apidocs/org/dual/hexa/core/tokens/port/in/IApiTokens.html)`#resolve`, da usare solo nel punto che chiama il servizio.

## Backup

`java -jar app.jar export <file>` e `import <file> [--replace]` salvano e ripristinano database e binari. Il database è copiato per intero; i binari sono quelli **referenziati** dal database, che l'app dichiara implementando [`IBlobReferences`](https://sdurz75.github.io/deep-flux/apidocs/org/dual/hexa/core/backup/port/in/IBlobReferences.html) (le coppie tabella e colonna con i nomi dei file; vedi `ExampleBlobReferences`). Un test dell'app fa fallire il build se una colonna `%filename%` non è dichiarata. L'archivio è cifrato con `HX_BACKUP_ENCRYPTION_KEY` (o la chiave WebDAV); senza chiave serve `--no-encrypt`.

## Riferimento API

[`IImageStorageService`](https://sdurz75.github.io/deep-flux/apidocs/org/dual/hexa/core/storage/port/in/IImageStorageService.html), [`IApiTokens`](https://sdurz75.github.io/deep-flux/apidocs/org/dual/hexa/core/tokens/port/in/IApiTokens.html), [`ISecretCipher`](https://sdurz75.github.io/deep-flux/apidocs/org/dual/hexa/core/secrets/port/in/ISecretCipher.html), [`IBlobReferences`](https://sdurz75.github.io/deep-flux/apidocs/org/dual/hexa/core/backup/port/in/IBlobReferences.html).
