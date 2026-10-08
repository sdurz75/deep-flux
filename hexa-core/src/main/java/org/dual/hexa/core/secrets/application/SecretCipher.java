package org.dual.hexa.core.secrets.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.dual.hexa.core.kernel.crypto.ChunkedAesGcmCipher;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.dual.hexa.core.secrets.domain.SecretException;
import org.dual.hexa.core.secrets.port.in.ISecretCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Cifratura dei segreti salvati nel DB (token API, password, segreti dei moduli: {@code ISecrets}) con la STESSA chiave e lo
 * STESSO algoritmo dei binari su WebDAV ({@link ChunkedAesGcmCipher}, AES-256-GCM): {@code app.secrets.encryption-key}
 * vale {@code ${HX_STORAGE_WEBDAV_ENCRYPTION_KEY}}, nessun segreto nuovo da gestire.
 *
 * <p>A differenza dello storage WebDAV, la chiave NON e' obbligatoria all'avvio (con {@code storage.type=local}, il default,
 * oggi puo' mancare, e il segnaposto di {@code .env.example} non e' base64 valido): il bean esiste sempre, e senza una
 * chiave valida {@link #isConfigured()} e' falso e cifrare/decifrare lancia {@link SecretException} di tipo CONFIGURATION
 * (la pagina {@code /secrets} lo spiega all'utente). Persa la chiave, i segreti sono irrecuperabili, come i binari.
 */
@Component
public class SecretCipher implements ISecretCipher {

    private final ChunkedAesGcmCipher cipher;
    private final String problem;
    private final Messages messages;

    public SecretCipher(@Value("${app.secrets.encryption-key:}") String base64Key, Messages messages) {
        this.messages = messages;
        ChunkedAesGcmCipher built = null;
        String reason = null;
        try {
            built = new ChunkedAesGcmCipher(ChunkedAesGcmCipher.keyFromBase64(base64Key));
        } catch (IllegalArgumentException e) {
            reason = e.getMessage();
        }
        this.cipher = built;
        this.problem = reason;
    }

    @Override
    public boolean isConfigured() {
        return cipher != null;
    }

    @Override
    public byte[] encrypt(String plain) {
        requireConfigured();
        return cipher.encryptBytes(plain.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String decrypt(byte[] encrypted) {
        requireConfigured();
        try {
            return new String(cipher.decryptBytes(encrypted), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Manomesso o cifrato con un'altra chiave: non e' input dell'utente, e' un guasto di configurazione.
            throw new SecretException(messages.get("secrets.error.cannotDecrypt"), e);
        }
    }

    private void requireConfigured() {
        if (cipher == null) {
            throw new SecretException(messages.get("secrets.error.keyMissing", problem == null ? "" : problem), null);
        }
    }
}
