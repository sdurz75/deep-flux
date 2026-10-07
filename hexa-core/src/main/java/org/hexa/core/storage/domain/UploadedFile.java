package org.hexa.core.storage.domain;

import java.io.IOException;
import java.io.InputStream;

/**
 * File caricato dall'utente, indipendente dal framework web (l'adapter in ne costruisce uno da un {@code MultipartFile}:
 * {@code new UploadedFile(f.getOriginalFilename(), f.getSize(), f::getInputStream)}). {@code size} e' la dimensione
 * dichiarata dal contenitore; il contenuto si apre in modo pigro, cosi' un errore di lettura lo traduce lo storage.
 */
public record UploadedFile(String originalFilename, long size, Content content) {

    @FunctionalInterface
    public interface Content {
        InputStream open() throws IOException;
    }

    /** Per i chiamanti che hanno gia' i byte (test, enhance). */
    public static UploadedFile of(String originalFilename, byte[] bytes) {
        return new UploadedFile(originalFilename, bytes.length, () -> new java.io.ByteArrayInputStream(bytes));
    }
}
