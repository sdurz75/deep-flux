package org.dual.replicate.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import org.dual.replicate.i18n.Messages;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

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

    /** Scarica {@code sourceUrl} e salva il risultato come {@code "<generationId>.<ext>"}. Ritorna il filename. */
    public String downloadAndStore(Long generationId, String sourceUrl) {
        try {
            Files.createDirectories(imagesDir);
        } catch (IOException e) {
            throw new UncheckedIOException(messages.get("imagestorage.error.createDir", imagesDir), e);
        }

        byte[] bytes = restClient.get()
                .uri(URI.create(sourceUrl))
                .retrieve()
                .body(byte[].class);

        String extension = extensionFrom(sourceUrl);
        String filename = generationId + "." + extension;
        Path target = imagesDir.resolve(filename);
        try {
            Files.write(target, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(messages.get("imagestorage.error.saveImage", target), e);
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
