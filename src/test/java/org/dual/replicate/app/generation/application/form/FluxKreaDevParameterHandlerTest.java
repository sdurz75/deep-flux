package org.dual.replicate.app.generation.application.form;

import java.util.Map;

import org.dual.replicate.app.generation.domain.GenerationFormType;
import org.dual.replicate.app.generation.port.in.IGenerationForms;
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
    void numOutputsIsClampedToTheGlobalLimit() {
        assertThat(handler.toParameterMap(Map.of("num_outputs", "9"))).containsEntry("num_outputs", IGenerationForms.MAX_NUM_OUTPUTS);
        assertThat(handler.toParameterMap(Map.of("num_outputs", "0"))).containsEntry("num_outputs", 1);
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
                .containsEntry("output_quality", 80)
                .containsEntry("guidance", 3.0)
                .doesNotContainKey("seed");
    }

    /** Enum dello schema Replicate: solo 0.25 e 1; un valore ereditato da klein-9b (0.5/2/4) non deve arrivare a Replicate come 422. */
    @Test
    void megapixelsFollowTheSchemaEnum() {
        assertThat(handler.toParameterMap(Map.of("megapixels", "0.25"))).containsEntry("megapixels", "0.25");
        assertThat(handler.toParameterMap(Map.of("megapixels", "1"))).containsEntry("megapixels", "1");
        for (String foreign : new String[]{"0.5", "2", "4", "abc"}) {
            assertThat(handler.toParameterMap(Map.of("megapixels", foreign))).doesNotContainKey("megapixels");
        }
    }
}
