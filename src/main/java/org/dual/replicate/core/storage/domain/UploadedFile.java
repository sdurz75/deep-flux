package org.dual.replicate.core.storage.domain;

import java.io.InputStream;

/**
 * File caricato dall'utente, indipendente dal framework web (l'adapter in costruisce uno da un {@code MultipartFile}).
 * {@code size} e' la dimensione dichiarata dal contenitore; {@code content} e' consumato (e chiuso) una sola volta.
 */
public record UploadedFile(String originalFilename, long size, InputStream content) {
}
