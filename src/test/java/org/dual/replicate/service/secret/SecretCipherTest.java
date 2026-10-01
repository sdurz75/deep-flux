package org.dual.replicate.service.secret;

import java.util.Base64;

import org.dual.replicate.core.kernel.i18n.Messages;
import org.dual.replicate.core.kernel.remote.RemoteServiceException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class SecretCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String OTHER_KEY = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    private final Messages messages = mock(Messages.class);

    @Test
    void roundTripsAndNeverStoresThePlaintext() {
        SecretCipher cipher = new SecretCipher(KEY, messages);

        byte[] encrypted = cipher.encrypt("hf_secret_token_1234");

        assertThat(cipher.isConfigured()).isTrue();
        assertThat(new String(encrypted, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("hf_secret_token_1234");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("hf_secret_token_1234");
        // nonce casuale: lo stesso testo non cifra mai due volte allo stesso modo
        assertThat(cipher.encrypt("hf_secret_token_1234")).isNotEqualTo(encrypted);
    }

    @Test
    void withoutAValidKeyItIsNotConfiguredAndRefusesToWork() {
        for (String bad : new String[] {"", null, "   ", "non-base64!!", Base64.getEncoder().encodeToString(new byte[16])}) {
            SecretCipher cipher = new SecretCipher(bad, messages);

            assertThat(cipher.isConfigured()).as("chiave %s", bad).isFalse();
            assertThatThrownBy(() -> cipher.encrypt("x")).isInstanceOf(SecretException.class)
                    .extracting(e -> ((RemoteServiceException) e).kind()).isEqualTo(RemoteServiceException.Kind.CONFIGURATION);
            assertThatThrownBy(() -> cipher.decrypt(new byte[40])).isInstanceOf(SecretException.class);
        }
    }

    @Test
    void aBlobEncryptedWithAnotherKeyOrTamperedFailsAsConfigurationError() {
        byte[] encrypted = new SecretCipher(KEY, messages).encrypt("secret");
        SecretCipher other = new SecretCipher(OTHER_KEY, messages);

        assertThatThrownBy(() -> other.decrypt(encrypted)).isInstanceOf(SecretException.class)
                .extracting(e -> ((RemoteServiceException) e).kind()).isEqualTo(RemoteServiceException.Kind.CONFIGURATION);

        byte[] tampered = encrypted.clone();
        tampered[tampered.length - 1] ^= 1;
        assertThatThrownBy(() -> new SecretCipher(KEY, messages).decrypt(tampered)).isInstanceOf(SecretException.class);
    }
}
