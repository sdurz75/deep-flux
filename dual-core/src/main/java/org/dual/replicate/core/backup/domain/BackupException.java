package org.dual.replicate.core.backup.domain;

/**
 * Errore atteso di un backup o di un ripristino (chiave mancante, archivio non valido, database non vergine...): il messaggio e' gia' tradotto
 * ({@code backup.error.*}) e finisce cosi' com'e' sulla console. Non e' un guasto di un servizio remoto, quindi non passa dal registro eventi.
 */
public class BackupException extends RuntimeException {

    public BackupException(String message) {
        super(message);
    }

    public BackupException(String message, Throwable cause) {
        super(message, cause);
    }
}
