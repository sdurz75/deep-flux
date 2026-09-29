package org.dual.replicate.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Locale;

import org.dual.replicate.i18n.Messages;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Scarica l'immagine generata da un URL Replicate e la salva sotto
 * storage.images-dir. Le immagini vivono fuori da static/ perche' sono
 * stato applicativo prodotto a runtime, non asset del progetto (vedi
 * StorageConfig per come vengono poi servite via /images/**).
 */
@Service
public class ImageStorageService {

    private final RestClient restClient = RestClient.create();
    private final Path imagesDir;
    private final Messages messages;

    public ImageStorageService(@Value("${storage.images-dir}") String imagesDir, Messages messages) {
        this.imagesDir = Path.of(imagesDir);
        this.messages = messages;
    }

    /**
     * Scarica {@code sourceUrl} e salva il risultato come
     * {@code "<generationId>-<index>.<ext>"}. Ritorna il filename.
     * {@code index} (posizione nella lista di output della prediction,
     * vedi PredictionResponse.outputUrls) evita che due immagini della
     * stessa generazione (num_outputs > 1) si sovrascrivano a vicenda:
     * senza di esso il nome dipenderebbe solo da generationId, identico
     * per tutte le immagini di una stessa richiesta.
     */
    public String downloadAndStore(Long generationId, int index, String sourceUrl) {
        try {
            Files.createDirectories(imagesDir);
        } catch (IOException e) {
            throw new UncheckedIOException(messages.get("imagestorage.error.createDir", imagesDir), e);
        }

        String extension = extensionFrom(sourceUrl);
        String filename = generationId + "-" + index + "." + extension;
        Path target = imagesDir.resolve(filename);
        // In streaming direttamente su file: un video puo' pesare decine di MB.
        try {
            restClient.get()
                    .uri(URI.create(sourceUrl))
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isError()) {
                            throw new IOException("HTTP " + response.getStatusCode().value() + " da " + sourceUrl);
                        }
                        Files.copy(response.getBody(), target, StandardCopyOption.REPLACE_EXISTING);
                        return null;
                    });
        } catch (RestClientException e) {
            // RestClient incapsula l'IOException dell'exchange (download o scrittura) in ResourceAccessException.
            throw new UncheckedIOException(messages.get("imagestorage.error.saveImage", target),
                    e.getCause() instanceof IOException io ? io : new IOException(e));
        }
        return filename;
    }

    /** Elimina il file immagine {@code filename} sotto storage.images-dir, se presente. No-op se {@code filename} e' null. */
    public void delete(String filename) {
        if (filename == null) {
            return;
        }
        try {
            Files.deleteIfExists(imagesDir.resolve(filename));
        } catch (IOException e) {
            throw new UncheckedIOException(messages.get("imagestorage.error.deleteImage", filename), e);
        }
    }

    /**
     * Legge {@code filename} come data-URI ("data:image/png;base64,..."):
     * il modo di passare a Replicate un'immagine locale (img2video) senza
     * un URL pubblico. Il filename arriva dal database, ma viene comunque
     * confinato sotto storage.images-dir.
     */
    public String readAsDataUri(String filename) {
        Path source = imagesDir.resolve(filename).normalize();
        if (!source.startsWith(imagesDir.normalize())) {
            throw new IllegalArgumentException(filename);
        }
        try {
            String extension = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            String mime = switch (extension) {
                case "jpg", "jpeg" -> "image/jpeg";
                case "webp" -> "image/webp";
                default -> "image/png";
            };
            return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(source));
        } catch (IOException e) {
            throw new UncheckedIOException(messages.get("imagestorage.error.readImage", filename), e);
        }
    }

    private String extensionFrom(String url) {
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
