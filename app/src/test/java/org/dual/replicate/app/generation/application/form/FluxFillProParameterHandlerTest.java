package org.dual.replicate.app.generation.application.form;

import java.util.Map;

import org.dual.replicate.app.generation.domain.GenerationFormType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FluxFillProParameterHandlerTest {

    private final FluxFillProParameterHandler handler = new FluxFillProParameterHandler();

    @Test
    void formTypeIsAnInpaintingEditTypeWithoutDisableSafetyChecker() {
        assertThat(handler.formType()).isEqualTo(GenerationFormType.FLUX_FILL_PRO);
        assertThat(handler.formType().sourceRequired()).isTrue();
        assertThat(handler.formType().takesMask()).isTrue();
        assertThat(handler.formType().sourceImageParam()).isEqualTo("image");
        assertThat(handler.formType().maskParam()).isEqualTo("mask");
        // Ha safety_tolerance (forzata dal servizio, non un campo), non disable_safety_checker: il servizio non deve inviarlo.
        assertThat(GenerationFormType.FLUX_FILL_PRO.hasDisableSafetyChecker()).isFalse();
        assertThat(GenerationFormType.FLUX_FILL_PRO.safetyToleranceParam()).isEqualTo("safety_tolerance");
        assertThat(GenerationFormType.FLUX_FILL_DEV.safetyToleranceParam()).isNull();
        assertThat(GenerationFormType.FLUX_FILL_DEV.hasDisableSafetyChecker()).isTrue();
        assertThat(GenerationFormType.FLUX_KONTEXT_DEV.hasDisableSafetyChecker()).isTrue();
    }

    @Test
    void onlyThePublishedInputsAreKept() {
        Map<String, Object> params = handler.toParameterMap(Map.of("lora_weights", "a/b", "num_outputs", "3", "outpaint", "Make square",
                "image", "x", "mask", "y", "prompt", "z", "megapixels", "1", "safety_tolerance", "1"));

        // safety_tolerance non e' un campo: un valore inviato dal client non passa mai (la forza il servizio al massimo).
        assertThat(params).doesNotContainKeys("lora_weights", "num_outputs", "outpaint", "image", "mask", "prompt", "megapixels", "safety_tolerance");
    }

    @Test
    void valuesAreClampedAndWhitelisted() {
        Map<String, Object> params = handler.toParameterMap(Map.of("steps", "999", "guidance", "420", "output_format", "webp"));

        assertThat(params).containsEntry("steps", 50).containsEntry("guidance", 100.0).doesNotContainKey("output_format");
        Map<String, Object> low = handler.toParameterMap(Map.of("steps", "1", "guidance", "0", "output_format", "png"));
        assertThat(low).containsEntry("steps", 15).containsEntry("guidance", 1.5).containsEntry("output_format", "png");
    }

    @Test
    void promptUpsamplingIsTrueOnlyWhenItsKeyIsSubmitted() {
        assertThat(handler.toParameterMap(Map.of("prompt_upsampling", "true"))).containsEntry("prompt_upsampling", true);
        assertThat(handler.toParameterMap(Map.of())).containsEntry("prompt_upsampling", false);
    }

    @Test
    void defaultsAreThePermissiveFullQualityOnes() {
        assertThat(handler.defaultFields()).containsEntry("steps", 50).containsEntry("guidance", 60.0)
                .containsEntry("prompt_upsampling", false).doesNotContainKey("safety_tolerance")
                .containsEntry("output_format", "jpg").doesNotContainKey("seed");
    }

    @Test
    void blankSeedIsOmittedNotZero() {
        assertThat(handler.toParameterMap(Map.of("seed", ""))).doesNotContainKey("seed");
        assertThat(handler.toParameterMap(Map.of("seed", "7"))).containsEntry("seed", 7L);
    }
}
