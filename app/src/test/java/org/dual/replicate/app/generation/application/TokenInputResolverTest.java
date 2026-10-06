package org.dual.replicate.app.generation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;

import org.dual.replicate.core.kernel.remote.RemoteServiceException;
import org.dual.replicate.core.tokens.domain.TokenException;
import org.dual.replicate.core.tokens.port.in.IApiTokens;
import org.dual.replicate.core.tokens.port.out.IApiTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class TokenInputResolverTest {

    @Autowired
    private IApiTokens tokens;
    @Autowired
    private IApiTokenStore repository;
    @Autowired
    private TokenInputResolver resolver;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void resolveIntoSwapsChosenIdsForThePlaintextAndAlwaysDropsTheIds() {
        var hf = tokens.create("HUGGINGFACE", "Personale", "hf_secret_abcd", null);
        var civitai = tokens.create("CIVITAI", "Lavoro", "cv_secret_wxyz", null);

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
                .isInstanceOf(TokenException.class)
                .extracting(e -> ((RemoteServiceException) e).kind()).isEqualTo(RemoteServiceException.Kind.REJECTED);
    }
}
