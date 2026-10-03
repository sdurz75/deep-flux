package org.dual.replicate.app.generation.application.form;

import java.util.Map;

import org.dual.replicate.app.generation.domain.GenerationFormType;
import org.dual.replicate.app.generation.port.in.IGenerationForms;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FluxLoraFinetuneParameterHandlerTest {

    private final FluxLoraFinetuneParameterHandler handler = new FluxLoraFinetuneParameterHandler();

    @Test
    void formTypeIsFluxLoraFinetune() {
        assertThat(handler.formType()).isEqualTo(GenerationFormType.FLUX_LORA_FINETUNE);
    }

    /** Replicate non supporta piu' di 4 immagini per richiesta: il limite vale lato server, non solo come max HTML. */
    @Test
    void numOutputsIsClampedToTheGlobalLimit() {
        assertThat(IGenerationForms.MAX_NUM_OUTPUTS).isEqualTo(4);
        assertThat(handler.toParameterMap(Map.of("num_outputs", "3"))).containsEntry("num_outputs", 3);
        assertThat(handler.toParameterMap(Map.of("num_outputs", "4"))).containsEntry("num_outputs", 4);
        assertThat(handler.toParameterMap(Map.of("num_outputs", "5"))).containsEntry("num_outputs", 4);
        assertThat(handler.toParameterMap(Map.of("num_outputs", "100"))).containsEntry("num_outputs", 4);
        assertThat(handler.toParameterMap(Map.of("num_outputs", "0"))).containsEntry("num_outputs", 1);
        assertThat(handler.toParameterMap(Map.of("num_outputs", "-2"))).containsEntry("num_outputs", 1);
    }

    @Test
    void blankOrNonNumericNumOutputsIsOmitted() {
        assertThat(handler.toParameterMap(Map.of("num_outputs", ""))).doesNotContainKey("num_outputs");
        assertThat(handler.toParameterMap(Map.of("num_outputs", "abc"))).doesNotContainKey("num_outputs");
        assertThat(handler.toParameterMap(Map.of())).doesNotContainKey("num_outputs");
    }

    /** Il checkpoint base e' salvato come "model" ma il campo del form e' "flux_model" ("model" e' anche la select del modello Replicate). */
    @Test
    void toFormFieldsMapsTheFluxCheckpointBackToItsFormField() {
        assertThat(handler.toFormFields(Map.of("model", "schnell", "width", 512, "lora_scale", 0.5)))
                .containsEntry("flux_model", "schnell")
                .containsEntry("width", "512")
                .containsEntry("lora_scale", "0.5")
                .doesNotContainKey("model");
    }

    /** prompt_strength conta solo con un'immagine di partenza (img2img/inpainting): e' un campo del form con un default, e si legge tollerante. */
    @Test
    void promptStrengthIsAFormFieldWithADefault() {
        assertThat(handler.defaultFields()).containsEntry("prompt_strength", FluxLoraFinetuneParameterHandler.DEFAULT_PROMPT_STRENGTH);
        assertThat(handler.toParameterMap(Map.of("prompt_strength", "0.95"))).containsEntry("prompt_strength", 0.95);
        assertThat(handler.toParameterMap(Map.of("prompt_strength", ""))).doesNotContainKey("prompt_strength");
    }

    /** Sorgente e maschera sono opzionali: il fine-tune le accetta ma, restando text-to-image, non le pretende (a differenza di flux-fill-*). */
    @Test
    void loraFinetuneTakesSourceAndMaskButDoesNotRequireThem() {
        GenerationFormType type = GenerationFormType.FLUX_LORA_FINETUNE;
        assertThat(type.sourceImageParam()).isEqualTo("image");
        assertThat(type.maskParam()).isEqualTo("mask");
        assertThat(type.takesMask()).isTrue();
        assertThat(type.requiresMask()).isFalse();
        assertThat(type.isEdit()).isFalse();
        assertThat(GenerationFormType.FLUX_FILL_DEV.requiresMask()).isTrue();
        assertThat(GenerationFormType.FLUX_FILL_PRO.requiresMask()).isTrue();
    }

    /** Enum dello schema Replicate: solo 0.25 e 1 (niente step da 0,5), il resto e' un 422 e si scarta. */
    @Test
    void megapixelsFollowTheSchemaEnum() {
        assertThat(handler.defaultFields()).containsEntry("megapixels", "1");
        assertThat(handler.toParameterMap(Map.of("megapixels", "0.25"))).containsEntry("megapixels", "0.25");
        assertThat(handler.toParameterMap(Map.of("megapixels", "1"))).containsEntry("megapixels", "1");
        assertThat(handler.toParameterMap(Map.of("megapixels", "0.5"))).doesNotContainKey("megapixels");
        assertThat(handler.toParameterMap(Map.of("megapixels", "4"))).doesNotContainKey("megapixels");
    }

    /** Seconda LoRA dello schema: testo e scala si leggono tolleranti, vuoto = nessuna LoRA extra (la chiave si omette). */
    @Test
    void extraLoraIsAFormFieldWithDefaults() {
        assertThat(handler.defaultFields())
                .containsEntry("extra_lora", "")
                .containsEntry("extra_lora_scale", FluxLoraFinetuneParameterHandler.DEFAULT_EXTRA_LORA_SCALE);
        assertThat(handler.toParameterMap(Map.of("extra_lora", "owner/lora", "extra_lora_scale", "0.7")))
                .containsEntry("extra_lora", "owner/lora")
                .containsEntry("extra_lora_scale", 0.7);
        assertThat(handler.toParameterMap(Map.of("extra_lora", "  ", "extra_lora_scale", ""))).doesNotContainKeys("extra_lora", "extra_lora_scale");
        assertThat(handler.toFormFields(Map.of("extra_lora", "owner/lora", "extra_lora_scale", 0.7)))
                .containsEntry("extra_lora", "owner/lora")
                .containsEntry("extra_lora_scale", "0.7");
    }
}
