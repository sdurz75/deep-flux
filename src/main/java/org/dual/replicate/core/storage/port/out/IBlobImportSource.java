package org.dual.replicate.core.storage.port.out;

import java.io.IOException;
import java.util.List;

import org.dual.replicate.core.storage.domain.ImportableFile;

/**
 * Origine di una migrazione di file esistenti (oggi: la cartella locale {@code storage.images-dir}). Tiene fuori dallo use case
 * l'accesso al filesystem: elenco e cancellazione stanno nell'adapter.
 */
public interface IBlobImportSource {

    /** I file da importare, in ordine stabile; niente temporanei ({@code .part}). Vuoto se l'origine non esiste. */
    List<ImportableFile> list() throws IOException;

    /** Elimina l'originale (dopo che l'import e' stato verificato). */
    void delete(ImportableFile file) throws IOException;
}
