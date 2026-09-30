package org.dual.replicate.service.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.NoSuchFileException;
import java.util.Base64;
import java.util.OptionalLong;

import org.dual.replicate.i18n.Messages;
import org.dual.replicate.remote.RemoteServiceException.Kind;
import org.dual.replicate.remote.RemoteCaller;
import org.dual.replicate.remote.RestClientTranslator;
import org.dual.replicate.remote.RetryPolicy;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.dual.replicate.service.PromptEnhancementService;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MultipartFile;

/**
 * Logica comune a ogni backend: download da URL Replicate, nomi dei file, validazione degli upload dai magic bytes,
 * data-URI, confinamento dei filename. I sottotipi implementano solo le primitive di persistenza
 * ({@link #write}, {@link #remove}, {@link #size}, {@link #openRange}).
 */
public abstract class AbstractImageStorageService implements IImageStorageService {

    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    protected final Messages messages;
    private final RestClient restClient;

    /**
     * Il RestClient.Builder auto-configurato (non {@code RestClient.create()}) porta i timeout globali di
     * {@code spring.http.clients.*}: senza, un download appeso bloccherebbe il thread per sempre.
     */
    protected AbstractImageStorageService(Messages messages, RestClient.Builder restClientBuilder) {
        this.messages = messages;
        this.restClient = restClientBuilder.build();
    }

    /**
     * Scrive {@code in} come {@code filename} in modo atomico: mai un file parziale col nome definitivo, e a
     * fallimento niente resti (temporanei, blob remoti a meta').
     */
    protected abstract void write(String filename, InputStream in) throws IOException;

    /** Rimuove {@code filename}; non e' un errore se non esiste. */
    protected abstract void remove(String filename) throws IOException;

    @Override
    public String downloadAndStore(String sourceUrl) {
        String filename = newFilename(extensionFrom(sourceUrl));
        // Un blip di rete durante il download di una prediction RIUSCITA la farebbe fallire (gli URL di output scadono):
        // si ritenta l'intero download+scrittura (idempotente: stesso nome, scrittura atomica). Se il backend ritenta gia'
        // da se' (WebDAV) i tentativi si moltiplicano (fino a 3x3): accettato, e' un caso sporadico e l'esito peggiore e'
        // solo qualche richiesta in piu' prima di fallire.
        RemoteCaller.builder(e -> new StorageException(messages.get("imagestorage.error.saveImage", filename), e,
                        downloadFailureKind(e)))
                .retry(RetryPolicy.DEFAULT).build()
                .call("downloadOutput", () -> restClient.get()
                        .uri(URI.create(sourceUrl))
                        .exchange((request, response) -> {
                            if (response.getStatusCode().isError()) {
                                int status = response.getStatusCode().value();
                                throw new StorageException("HTTP " + status + " da " + sourceUrl, null,
                                        RestClientTranslator.kindOfStatus(status));
                            }
                            write(filename, response.getBody());
                            return null;
                        }));
        return filename;
    }

    private static Kind downloadFailureKind(Throwable e) {
        if (e instanceof RestClientResponseException response) {
            return RestClientTranslator.kindOfStatus(response.getStatusCode().value());
        }
        return e instanceof ResourceAccessException ? Kind.TRANSIENT : Kind.PERMANENT;
    }

    /**
     * Il nome del file e' interamente generato qui (nessun path traversal dal nome originale dell'upload), come per
     * ogni altro binario: vedi {@link #newFilename}.
     */
    @Override
    public String storeUpload(MultipartFile upload) {
        SourceUpload checked = checkUpload(upload);
        String filename = newFilename(checked.extension());
        try {
            write(filename, new ByteArrayInputStream(checked.bytes()));
            return filename;
        } catch (IOException e) {
            throw new StorageException(messages.get("imagestorage.error.saveImage", upload.getOriginalFilename()), e, Kind.PERMANENT);
        }
    }

    @Override
    public PromptEnhancementService.SourceImage inspectUpload(MultipartFile upload) {
        SourceUpload checked = checkUpload(upload);
        return new PromptEnhancementService.SourceImage(checked.bytes(), IImageStorageService.mimeOf("x." + checked.extension()));
    }

    @Override
    public PromptEnhancementService.SourceImage read(String filename) {
        return new PromptEnhancementService.SourceImage(readAllBytes(filename), IImageStorageService.mimeOf(filename));
    }

    @Override
    public String readAsDataUri(String filename) {
        return "data:" + IImageStorageService.mimeOf(filename) + ";base64,"
                + Base64.getEncoder().encodeToString(readAllBytes(filename));
    }

    @Override
    public void delete(String filename) {
        if (filename == null) {
            return;
        }
        checkFilename(filename);
        try {
            remove(filename);
        } catch (IOException e) {
            throw new StorageException(messages.get("imagestorage.error.deleteImage", filename), e, Kind.PERMANENT);
        }
    }

    private byte[] readAllBytes(String filename) {
        checkFilename(filename);
        try {
            OptionalLong size = size(filename);
            if (size.isEmpty()) {
                throw new NoSuchFileException(filename);
            }
            try (InputStream in = openRange(filename, 0, size.getAsLong())) {
                return in.readAllBytes();
            }
        } catch (NoSuchFileException e) {
            // Atteso (riga senza file, file gia' cancellato): un esito, non un guasto dello storage.
            throw new StorageException(messages.get("imagestorage.error.readImage", filename), e, Kind.REJECTED);
        } catch (IOException e) {
            throw new StorageException(messages.get("imagestorage.error.readImage", filename), e, Kind.PERMANENT);
        }
    }

    private SourceUpload checkUpload(MultipartFile upload) {
        if (upload.getSize() > MAX_UPLOAD_BYTES) {
            throw new StorageException(messages.get("imagestorage.error.uploadTooLarge", MAX_UPLOAD_BYTES / (1024 * 1024)), null, Kind.REJECTED);
        }
        try {
            byte[] bytes = upload.getBytes();
            String extension = imageExtensionOf(bytes);
            if (extension == null) {
                throw new StorageException(messages.get("imagestorage.error.uploadInvalidType"), null, Kind.REJECTED);
            }
            return new SourceUpload(bytes, extension);
        } catch (IOException e) {
            throw new StorageException(messages.get("imagestorage.error.readImage", upload.getOriginalFilename()), e, Kind.PERMANENT);
        }
    }

    private record SourceUpload(byte[] bytes, String extension) {
    }

    /**
     * Nome di un nuovo binario: SHA-256 (hex) di 32 byte casuali + {@code .<extension>}. Mai derivato da id o dal
     * nome originale: e' unico anche dopo un reset del DB (gli id ripartirebbero da 1) e non rivela nulla del
     * contenuto (su WebDAV il file e' cifrato: l'estensione e' solo un'indicazione utile). Non e' un hash del
     * contenuto: ogni riga ha il suo file, quindi cancellarne una non tocca le altre.
     */
    static String newFilename(String extension) {
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(random))
                    + "." + extension;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Percorso fisico annidato {@code ab/cd/<filename>}, dove {@code abcd} sono i primi 2 byte (hex) dello SHA-256 del
     * filename: deterministico, quindi derivabile dal solo filename salvato nel DB. Uguale per ogni backend.
     */
    public static String shardPath(String filename) {
        checkFilename(filename);
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(filename.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return String.format("%02x/%02x/%s", d[0] & 0xFF, d[1] & 0xFF, filename);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Il filename logico e' a un solo livello (il layout fisico annidato lo da'
     * {@link #shardPath}), niente traversal: vale per qualunque backend (il filename arriva dal DB o dall'URL). */
    protected static void checkFilename(String filename) {
        if (filename == null || filename.isEmpty() || filename.contains("/") || filename.contains("\\")
                || filename.contains("..")) {
            throw new IllegalArgumentException(String.valueOf(filename));
        }
    }

    private static String imageExtensionOf(byte[] b) {
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "png";
        }
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "webp";
        }
        return null;
    }

    private static String extensionFrom(String url) {
        String path = URI.create(url).getPath();
        int dot = path.lastIndexOf('.');
        if (dot >= 0 && dot < path.length() - 1) {
            String ext = path.substring(dot + 1);
            if (ext.length() <= 5) {
                return ext;
            }
        }
        return "png";
    }
}
