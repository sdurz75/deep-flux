package org.dual.replicate.app.generation.application.form;

import java.util.Map;

import org.dual.replicate.app.generation.domain.GenerationFormType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FluxFillDevParameterHandlerTest {

    private final FluxFillDevParameterHandler handler = new FluxFillDevParameterHandler();

    @Test
    void formTypeIsAnInpaintingEditTypeWithImageAndMask() {
        assertThat(handler.formType()).isEqualTo(GenerationFormType.FLUX_FILL_DEV);
        assertThat(handler.formType().sourceRequired()).isTrue();
        assertThat(handler.formType().sourceImageParam()).isEqualTo("image");
        assertThat(handler.formType().maskParam()).isEqualTo("mask");
        assertThat(handler.formType().takesMask()).isTrue();
        assertThat(GenerationFormType.FLUX_KONTEXT_DEV.takesMask()).isFalse();
    }

    @Test
    void imageMaskAndPromptAreNotFormFields() {
        Map<String, Object> params = handler.toParameterMap(Map.of("image", "x", "mask", "y", "prompt", "z", "extra_lora", "a/b"));

        assertThat(params).doesNotContainKeys("image", "mask", "prompt", "extra_lora");
    }

    @Test
    void valuesAreClampedAndWhitelisted() {
        Map<String, Object> params = handler.toParameterMap(Map.of("num_inference_steps", "999", "guidance", "420",
                "lora_scale", "9", "output_quality", "-5", "megapixels", "4", "output_format", "gif", "num_outputs", "9"));

        assertThat(params).containsEntry("num_inference_steps", 50).containsEntry("guidance", 100.0)
                .containsEntry("lora_scale", 3.0).containsEntry("output_quality", 0)
                .containsEntry("num_outputs", 4)
                .doesNotContainKeys("megapixels", "output_format");
    }

    @Test
    void loraWeightsAreKeptAndBlankMeansNoLora() {
        assertThat(handler.toParameterMap(Map.of("lora_weights", "owner/my-lora"))).containsEntry("lora_weights", "owner/my-lora");
        assertThat(handler.toParameterMap(Map.of("lora_weights", "  "))).doesNotContainKey("lora_weights");
    }

    @Test
    void matchInputIsAnAcceptedMegapixelsValueAndTheDefault() {
        assertThat(handler.toParameterMap(Map.of("megapixels", "match_input"))).containsEntry("megapixels", "match_input");
        assertThat(handler.defaultFields()).containsEntry("megapixels", "match_input").containsEntry("guidance", 30.0)
                .containsEntry("num_inference_steps", 28).containsEntry("lora_scale", 1.0).doesNotContainKey("seed");
    }

    @Test
    void blankSeedIsOmittedNotZero() {
        assertThat(handler.toParameterMap(Map.of("seed", ""))).doesNotContainKey("seed");
        assertThat(handler.toParameterMap(Map.of("seed", "7"))).containsEntry("seed", 7L);
    }
}
