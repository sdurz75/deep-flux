package org.dual.replicate.app.generation.application;

import java.util.Map;

import org.dual.replicate.app.generation.domain.GenerationFormType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FluxKontextDevParameterHandlerTest {

    private final FluxKontextDevParameterHandler handler = new FluxKontextDevParameterHandler();

    @Test
    void formTypeIsAnEditTypeWithInputImageAsSource() {
        assertThat(handler.formType()).isEqualTo(GenerationFormType.FLUX_KONTEXT_DEV);
        assertThat(handler.formType().isEdit()).isTrue();
        assertThat(handler.formType().sourceImageParam()).isEqualTo("input_image");
    }

    @Test
    void goFastIsTrueOnlyWhenItsKeyIsSubmitted() {
        assertThat(handler.toParameterMap(Map.of("go_fast", "true"))).containsEntry("go_fast", true);
        assertThat(handler.toParameterMap(Map.of())).containsEntry("go_fast", false);
    }

    @Test
    void valuesAreClampedAndWhitelisted() {
        Map<String, Object> params = handler.toParameterMap(Map.of("num_inference_steps", "999", "guidance", "42",
                "output_quality", "-5", "aspect_ratio", "7:5", "output_format", "gif"));

        assertThat(params).containsEntry("num_inference_steps", 50).containsEntry("guidance", 10.0)
                .containsEntry("output_quality", 0)
                .doesNotContainKeys("aspect_ratio", "output_format");
    }

    @Test
    void matchInputImageIsAnAcceptedAspectRatioAndTheDefault() {
        assertThat(handler.toParameterMap(Map.of("aspect_ratio", "match_input_image")))
                .containsEntry("aspect_ratio", "match_input_image");
        assertThat(handler.defaultFields()).containsEntry("aspect_ratio", "match_input_image")
                .containsEntry("go_fast", false).containsEntry("output_format", "jpg").doesNotContainKey("seed");
    }

    @Test
    void blankSeedIsOmittedNotZero() {
        assertThat(handler.toParameterMap(Map.of("seed", ""))).doesNotContainKey("seed");
        assertThat(handler.toParameterMap(Map.of("seed", "7"))).containsEntry("seed", 7L);
    }
}
