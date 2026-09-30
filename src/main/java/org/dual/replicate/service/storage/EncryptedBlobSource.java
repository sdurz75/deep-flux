package org.dual.replicate.service.storage;

import java.io.IOException;
import java.io.InputStream;

/**
 * Dove stanno i byte CIFRATI di un blob: la cache locale ({@link FileBlobSource}) o il server WebDAV. Il cifrario
 * ({@link ChunkedAesGcmCipher}) decifra da qualunque sorgente allo stesso modo, leggendo solo i range che gli servono.
 */
interface EncryptedBlobSource {

    /** Lunghezza totale del blob cifrato. {@link java.nio.file.NoSuchFileException} se non esiste. */
    long length() throws IOException;

    /** {@code len} byte a partire da {@code offset} (meno, se il blob finisce prima). Chi riceve lo stream lo chiude. */
    InputStream read(long offset, long len) throws IOException;
}
