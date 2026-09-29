package org.dual.replicate.service;

import java.util.Map;

import org.dual.replicate.domain.GenerationFormType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FluxKreaDevParameterHandlerTest {

    private final FluxKreaDevParameterHandler handler = new FluxKreaDevParameterHandler();

    @Test
    void formTypeIsFluxKreaDev() {
        assertThat(handler.formType()).isEqualTo(GenerationFormType.FLUX_KREA_DEV);
    }

    @Test
    void goFastIsTrueOnlyWhenItsKeyIsSubmitted() {
        assertThat(handler.toParameterMap(Map.of("go_fast", "true"))).containsEntry("go_fast", true);
        assertThat(handler.toParameterMap(Map.of())).containsEntry("go_fast", false);
    }

    @Test
    void blankSeedIsOmittedNotZero() {
        assertThat(handler.toParameterMap(Map.of("seed", ""))).doesNotContainKey("seed");
        assertThat(handler.toParameterMap(Map.of("seed", "42"))).containsEntry("seed", 42L);
    }

    @Test
    void guidanceIsParsedAsDouble() {
        assertThat(handler.toParameterMap(Map.of("guidance", "2.5"))).containsEntry("guidance", 2.5);
        assertThat(handler.toParameterMap(Map.of())).doesNotContainKey("guidance");
    }

    @Test
    void defaultFieldsMatchAppDefaults() {
        Map<String, Object> defaults = handler.defaultFields();
        assertThat(defaults)
                .containsEntry("aspect_ratio", "1:1")
                .containsEntry("megapixels", "1")
                // go_fast=false e' una scelta deliberata dell'app, diversa dal default Replicate (true).
                .containsEntry("go_fast", false)
                .containsEntry("num_outputs", 1)
                .containsEntry("output_format", "jpg")
                .containsEntry("num_inference_steps", 28)
                .doesNotContainKey("seed")
                .doesNotContainKey("guidance")
                .doesNotContainKey("output_quality");
    }
}
