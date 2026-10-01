package org.dual.replicate.core.secrets.port.in;

/**
 * Cifratura dei segreti salvati nel DB (token API, ...). Senza una chiave valida {@link #isConfigured()} e' falso e
 * cifrare/decifrare lancia {@code SecretException} (CONFIGURATION): la chiave non e' obbligatoria all'avvio.
 */
public interface ISecretCipher {

    /** {@code true} se c'e' una chiave valida da 32 byte. */
    boolean isConfigured();

    byte[] encrypt(String plain);

    String decrypt(byte[] encrypted);
}
