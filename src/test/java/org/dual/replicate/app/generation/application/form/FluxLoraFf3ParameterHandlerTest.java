package org.dual.replicate.app.generation.application.form;

import java.util.Map;

import org.dual.replicate.app.generation.domain.GenerationFormType;
import org.dual.replicate.app.generation.port.in.IGenerationForms;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FluxLoraFf3ParameterHandlerTest {

    private final FluxLoraFf3ParameterHandler handler = new FluxLoraFf3ParameterHandler();

    @Test
    void formTypeIsFluxLoraFf3() {
        assertThat(handler.formType()).isEqualTo(GenerationFormType.FLUX_LORA_FF3);
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
}
