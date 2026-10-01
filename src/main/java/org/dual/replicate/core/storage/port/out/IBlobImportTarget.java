package org.dual.replicate.core.storage.port.out;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Destinazione di una migrazione di file esistenti (oggi: filesystem locale -> WebDAV cifrato). Implementata dal backend
 * che riceve i file; interroga il SERVER, non la cache locale, perche' serve all'idempotenza.
 */
public interface IBlobImportTarget {

    /** {@code true} se {@code filename} esiste gia' sul server. */
    boolean existsRemotely(String filename) throws IOException;

    /** Dimensione in chiaro di {@code filename} come risulta dal SERVER: verifica di un upload. */
    long remotePlainSize(String filename) throws IOException;

    /** Carica {@code file} come {@code filename} (come una scrittura normale). */
    void importFile(String filename, Path file) throws IOException;
}
