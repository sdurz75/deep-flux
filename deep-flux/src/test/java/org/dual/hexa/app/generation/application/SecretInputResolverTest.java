package org.dual.hexa.app.generation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;

import org.dual.hexa.core.kernel.remote.RemoteServiceException;
import org.dual.hexa.core.secrets.domain.SecretException;
import org.dual.hexa.core.secrets.port.in.ISecrets;
import org.dual.hexa.core.secrets.port.out.ISecretStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class SecretInputResolverTest {

    @Autowired
    private ISecrets secrets;
    @Autowired
    private ISecretStore repository;
    @Autowired
    private SecretInputResolver resolver;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void resolveIntoSwapsChosenIdsForThePlaintextAndAlwaysDropsTheIds() {
        var hf = secrets.create("HUGGINGFACE", "Personale", "hf_secret_abcd", null);
        var civitai = secrets.create("CIVITAI", "Lavoro", "cv_secret_wxyz", null);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("lora_weights", "owner/lora");
        input.put("hf_token_id", hf.id().intValue()); // da JSON: numero
        input.put("civitai_token_id", String.valueOf(civitai.id())); // da form: stringa
        resolver.resolveInto(input);

        assertThat(input).containsEntry("hf_api_token", "hf_secret_abcd").containsEntry("civitai_api_token", "cv_secret_wxyz")
                .containsEntry("lora_weights", "owner/lora").doesNotContainKeys("hf_token_id", "civitai_token_id");

        Map<String, Object> none = new LinkedHashMap<>(Map.of("hf_token_id", "", "civitai_token_id", "abc", "seed", 1));
        resolver.resolveInto(none);
        assertThat(none).containsExactly(Map.entry("seed", 1));

        assertThatThrownBy(() -> resolver.resolveInto(new LinkedHashMap<>(Map.of("hf_token_id", 999_999L))))
                .isInstanceOf(SecretException.class)
                .extracting(e -> ((RemoteServiceException) e).kind()).isEqualTo(RemoteServiceException.Kind.REJECTED);
    }
}
