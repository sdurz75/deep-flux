package org.dual.replicate.service.secret;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.dual.replicate.i18n.Messages;
import org.dual.replicate.service.TokenException;
import org.dual.replicate.service.storage.ChunkedAesGcmCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Cifratura dei segreti salvati nel DB (oggi i token CivitAI/HuggingFace, {@code ApiTokenService}) con la STESSA chiave e lo
 * STESSO algoritmo dei binari su WebDAV ({@link ChunkedAesGcmCipher}, AES-256-GCM): {@code app.secrets.encryption-key}
 * vale {@code ${STORAGE_WEBDAV_ENCRYPTION_KEY}}, nessun segreto nuovo da gestire.
 *
 * <p>A differenza dello storage WebDAV, la chiave NON e' obbligatoria all'avvio (con {@code storage.type=local}, il default,
 * oggi puo' mancare, e il segnaposto di {@code .env.example} non e' base64 valido): il bean esiste sempre, e senza una
 * chiave valida {@link #isConfigured()} e' falso e cifrare/decifrare lancia {@link TokenException} di tipo CONFIGURATION
 * (la pagina {@code /tokens} lo spiega all'utente). Persa la chiave, i segreti sono irrecuperabili, come i binari.
 */
@Component
public class SecretCipher {

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

    /** {@code true} se c'e' una chiave valida da 32 byte. */
    public boolean isConfigured() {
        return cipher != null;
    }

    public byte[] encrypt(String plain) {
        requireConfigured();
        return cipher.encryptBytes(plain.getBytes(StandardCharsets.UTF_8));
    }

    public String decrypt(byte[] encrypted) {
        requireConfigured();
        try {
            return new String(cipher.decryptBytes(encrypted), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Manomesso o cifrato con un'altra chiave: non e' input dell'utente, e' un guasto di configurazione.
            throw new TokenException(messages.get("tokens.error.cannotDecrypt"), e, TokenException.Kind.CONFIGURATION);
        }
    }

    private void requireConfigured() {
        if (cipher == null) {
            throw new TokenException(messages.get("tokens.error.keyMissing", problem == null ? "" : problem), null,
                    TokenException.Kind.CONFIGURATION);
        }
    }
}
