package org.dual.replicate.app.generation.application.form;

import java.util.Map;

import org.dual.replicate.app.generation.domain.GenerationFormType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Flux2Klein9bParameterHandlerTest {

    private final Flux2Klein9bParameterHandler handler = new Flux2Klein9bParameterHandler();

    @Test
    void formTypeIsFlux2Klein9b() {
        assertThat(handler.formType()).isEqualTo(GenerationFormType.FLUX_2_KLEIN_9B);
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
    void defaultFieldsMatchAppDefaults() {
        Map<String, Object> defaults = handler.defaultFields();
        assertThat(defaults)
                .containsEntry("aspect_ratio", "1:1")
                .containsEntry("megapixels", "1")
                // go_fast=false e' una scelta deliberata dell'app, diversa dal default Replicate (true).
                .containsEntry("go_fast", false)
                .containsEntry("output_format", "jpg")
                .containsEntry("output_quality", 80)
                .doesNotContainKey("seed");
    }
}
