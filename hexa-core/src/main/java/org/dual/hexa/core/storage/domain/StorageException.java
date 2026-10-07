package org.dual.hexa.core.storage.domain;

import org.dual.hexa.core.events.domain.CoreEventSource;
import org.dual.hexa.core.kernel.remote.RemoteServiceException;

/**
 * Errore dello storage dei binari (filesystem locale o WebDAV): l'unico tipo che {@code IImageStorageService} lancia verso
 * l'esterno (mai {@code UncheckedIOException}). {@code Kind.TRANSIENT} per rete/timeout/5xx di WebDAV, {@code PERMANENT}
 * per il resto (401/403, disco pieno, blob illeggibile), {@code REJECTED} per un esito atteso (file inesistente, upload
 * troppo grande o di tipo non valido: solo un messaggio all'utente, niente registro errori).
 */
public class StorageException extends RemoteServiceException {

    public StorageException(String message, Throwable cause, Kind kind) {
        super(CoreEventSource.STORAGE, kind, message, cause);
    }
}
