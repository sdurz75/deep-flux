package org.dual.replicate.app.generation.application.form;

import java.util.Map;

import org.dual.replicate.app.generation.domain.GenerationFormType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FluxDevLoraParameterHandlerTest {

    private final FluxDevLoraParameterHandler handler = new FluxDevLoraParameterHandler();

    @Test
    void formTypeIsFluxDevLoraAndTakesAnOptionalSourceImage() {
        assertThat(handler.formType()).isEqualTo(GenerationFormType.FLUX_DEV_LORA);
        assertThat(GenerationFormType.FLUX_DEV_LORA.sourceImageParam()).isEqualTo("image");
        assertThat(GenerationFormType.FLUX_DEV_LORA.sourceRequired()).isFalse();
    }

    @Test
    void loraFieldsAreMappedAndBlankOnesOmitted() {
        Map<String, Object> params = handler.toParameterMap(Map.of(
                "lora_weights", "  fofr/flux-pixar-cars ", "lora_scale", "0.8",
                "extra_lora", "", "extra_lora_scale", "1.5"));

        assertThat(params).containsEntry("lora_weights", "fofr/flux-pixar-cars")
                .containsEntry("lora_scale", 0.8)
                .containsEntry("extra_lora_scale", 1.5)
                .doesNotContainKey("extra_lora");
        assertThat(handler.toParameterMap(Map.of())).doesNotContainKeys("lora_weights", "extra_lora");
    }

    @Test
    void chosenTokenIdsAreParsedAndBlankOrInvalidOnesOmitted() {
        assertThat(handler.toParameterMap(Map.of("hf_token_id", "3", "civitai_token_id", "12")))
                .containsEntry("hf_token_id", 3L).containsEntry("civitai_token_id", 12L);
        assertThat(handler.toParameterMap(Map.of("hf_token_id", "", "civitai_token_id", "abc")))
                .doesNotContainKeys("hf_token_id", "civitai_token_id");
    }

    /** Il token in chiaro non e' piu' un campo del form: un valore con quel nome viene ignorato, mai inoltrato. */
    @Test
    void plaintextTokenFieldsAreNotAcceptedAnymore() {
        assertThat(handler.toParameterMap(Map.of("hf_api_token", "hf_x", "civitai_api_token", "cv_y")))
                .doesNotContainKeys("hf_api_token", "civitai_api_token");
    }

    @Test
    void aspectRatioAndMegapixelsOutsideTheModelEnumsAreDropped() {
        assertThat(handler.toParameterMap(Map.of("aspect_ratio", "custom", "megapixels", "4")))
                .doesNotContainKeys("aspect_ratio", "megapixels");
        assertThat(handler.toParameterMap(Map.of("aspect_ratio", "match_input_image")))
                .doesNotContainKey("aspect_ratio");
        assertThat(handler.toParameterMap(Map.of("aspect_ratio", "21:9", "megapixels", "0.25")))
                .containsEntry("aspect_ratio", "21:9").containsEntry("megapixels", "0.25");
    }

    @Test
    void goFastIsTrueOnlyWhenItsKeyIsSubmittedAndSeedBlankIsOmitted() {
        assertThat(handler.toParameterMap(Map.of("go_fast", "true"))).containsEntry("go_fast", true);
        assertThat(handler.toParameterMap(Map.of())).containsEntry("go_fast", false);
        assertThat(handler.toParameterMap(Map.of("seed", ""))).doesNotContainKey("seed");
        assertThat(handler.toParameterMap(Map.of("seed", "42"))).containsEntry("seed", 42L);
    }

    @Test
    void promptStrengthIsParsedAsDouble() {
        assertThat(handler.toParameterMap(Map.of("prompt_strength", "0.55"))).containsEntry("prompt_strength", 0.55);
        assertThat(handler.toParameterMap(Map.of())).doesNotContainKey("prompt_strength");
    }

    @Test
    void defaultFieldsMatchAppDefaultsAndPreselectNoToken() {
        assertThat(handler.defaultFields())
                .containsEntry("aspect_ratio", "1:1")
                .containsEntry("megapixels", "1")
                .containsEntry("go_fast", false)
                .containsEntry("num_outputs", 1)
                .containsEntry("output_format", "jpg")
                .containsEntry("num_inference_steps", 28)
                .containsEntry("output_quality", 80)
                .containsEntry("guidance", 3.0)
                .containsEntry("lora_scale", 1.0)
                .containsEntry("extra_lora_scale", 1.0)
                .containsEntry("prompt_strength", 0.8)
                .containsEntry("hf_token_id", "")
                .containsEntry("civitai_token_id", "")
                .containsEntry("lora_weights", "")
                .containsEntry("extra_lora", "")
                .doesNotContainKeys("seed", "hf_api_token", "civitai_api_token");
    }

    /** "Usa configurazione": i LoRA (con le scale e i token scelti) tornano nel form, i boolean come stringhe esplicite. */
    @Test
    void toFormFieldsRestoresLorasTokensAndBooleans() {
        Map<String, Object> saved = new java.util.LinkedHashMap<>();
        saved.put("lora_weights", "me/face-lora");
        saved.put("lora_scale", 0.9);
        saved.put("extra_lora", "fofr/other");
        saved.put("hf_token_id", 3L);
        saved.put("go_fast", false);
        saved.put("num_outputs", 2);
        saved.put("seed", 42L);
        saved.put("hf_api_token", "hf_secret");
        saved.put("unknown_key", "x");

        assertThat(handler.toFormFields(saved))
                .containsEntry("lora_weights", "me/face-lora")
                .containsEntry("lora_scale", "0.9")
                .containsEntry("extra_lora", "fofr/other")
                .containsEntry("hf_token_id", "3")
                .containsEntry("go_fast", "false")
                .containsEntry("num_outputs", "2")
                // Il seed non e' un campo dell'handler (lo porta la generazione), il token in chiaro e le chiavi ignote non passano mai.
                .doesNotContainKeys("seed", "hf_api_token", "unknown_key");
        assertThat(handler.toFormFields(Map.of())).isEmpty();
    }
}
