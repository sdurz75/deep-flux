package org.hexa.core.storage.port.out;

import java.io.IOException;
import java.io.InputStream;
import java.util.OptionalLong;

/**
 * Primitive di persistenza dei binari (filesystem locale, WebDAV cifrato...): tutto il resto (nomi, validazione, data-URI)
 * e' dello use case {@code ImageStorageService}. Il backend e' scelto da {@code storage.type}. I filename sono quelli
 * logici (un livello, gia' validati dallo use case); il layout fisico e' affare del backend ({@code StorageNames#shardPath}).
 */
public interface IBlobBackend {

    /**
     * Scrive {@code in} come {@code filename} in modo atomico: mai un file parziale col nome definitivo, e a
     * fallimento niente resti (temporanei, blob remoti a meta').
     */
    void write(String filename, InputStream in) throws IOException;

    /** Rimuove {@code filename}; non e' un errore se non esiste. */
    void remove(String filename) throws IOException;

    /** Dimensione in chiaro di {@code filename}; vuoto se non esiste. */
    OptionalLong size(String filename);

    /**
     * Apre {@code length} byte in chiaro a partire da {@code offset}; chi riceve lo stream lo chiude.
     * {@link java.nio.file.NoSuchFileException} se il file non esiste.
     */
    InputStream openRange(String filename, long offset, long length) throws IOException;
}
