package org.dual.hexa.core.backup.application;

import org.dual.hexa.core.backup.domain.BackupException;
import org.dual.hexa.core.kernel.crypto.ChunkedAesGcmCipher;
import org.dual.hexa.core.kernel.i18n.Messages;

/** La chiave AES-256 dell'archivio ({@code backup.encryption-key}, base64): una sola regola per export e import. */
final class BackupKeys {

    private static final int KEY_BYTES = 32;

    private BackupKeys() {
    }

    /** Per cifrare: senza una chiave valida non si esporta (mai un backup in chiaro per omissione). */
    static byte[] require(String base64, Messages messages) {
        if (base64 == null || base64.isBlank()) {
            throw new BackupException(messages.get("backup.error.keyMissing"));
        }
        byte[] key;
        try {
            key = ChunkedAesGcmCipher.keyFromBase64(base64);
        } catch (IllegalArgumentException e) {
            throw new BackupException(messages.get("backup.error.keyInvalid", e.getMessage()), e);
        }
        if (key.length != KEY_BYTES) {
            throw new BackupException(messages.get("backup.error.keyInvalid", key.length + " byte"));
        }
        return key;
    }

    /**
     * Per leggere: {@code null} se manca o non e' valida. Un archivio in chiaro si importa comunque (la chiave del {@code .env} potrebbe essere solo il
     * segnaposto di {@code .env.example}); se invece l'archivio e' cifrato, l'adapter dice che serve una chiave.
     */
    static byte[] lenient(String base64) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        try {
            byte[] key = ChunkedAesGcmCipher.keyFromBase64(base64);
            return key.length == KEY_BYTES ? key : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
