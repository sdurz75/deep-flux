package org.dual.hexa.app.generation.application.form;

import java.util.Map;

import org.dual.hexa.app.generation.domain.GenerationFormType;
import org.dual.hexa.app.generation.domain.GenerationKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PVideoParameterHandlerTest {

    private final PVideoParameterHandler handler = new PVideoParameterHandler();

    @Test
    void formTypeIsPVideoAndProducesVideo() {
        assertThat(handler.formType()).isEqualTo(GenerationFormType.P_VIDEO);
        assertThat(handler.formType().kind()).isEqualTo(GenerationKind.VIDEO);
    }

    @Test
    void checkboxesAreTrueOnlyWhenTheirKeyIsSubmitted() {
        assertThat(handler.toParameterMap(Map.of("draft", "true", "prompt_upsampling", "true")))
                .containsEntry("draft", true).containsEntry("prompt_upsampling", true);
        assertThat(handler.toParameterMap(Map.of()))
                .containsEntry("draft", false).containsEntry("prompt_upsampling", false);
    }

    @Test
    void durationIsClampedToTheModelRange() {
        assertThat(handler.toParameterMap(Map.of("duration", "99"))).containsEntry("duration", 20);
        assertThat(handler.toParameterMap(Map.of("duration", "0"))).containsEntry("duration", 1);
        assertThat(handler.toParameterMap(Map.of("duration", "7"))).containsEntry("duration", 7);
        assertThat(handler.toParameterMap(Map.of())).doesNotContainKey("duration");
    }

    @Test
    void valuesOutsideTheModelEnumsAreDropped() {
        assertThat(handler.toParameterMap(Map.of("fps", "30", "resolution", "4k", "aspect_ratio", "5:4")))
                .doesNotContainKeys("fps", "resolution", "aspect_ratio");
        assertThat(handler.toParameterMap(Map.of("fps", "48", "resolution", "1080p", "aspect_ratio", "9:16")))
                .containsEntry("fps", 48).containsEntry("resolution", "1080p").containsEntry("aspect_ratio", "9:16");
    }

    @Test
    void blankSeedIsOmittedNotZero() {
        assertThat(handler.toParameterMap(Map.of("seed", ""))).doesNotContainKey("seed");
        assertThat(handler.toParameterMap(Map.of("seed", "42"))).containsEntry("seed", 42L);
    }

    @Test
    void defaultFieldsMatchReplicateDefaults() {
        assertThat(handler.defaultFields())
                .containsEntry("duration", 5)
                .containsEntry("aspect_ratio", "16:9")
                .containsEntry("resolution", "720p")
                .containsEntry("fps", 24)
                .containsEntry("draft", false)
                .containsEntry("prompt_upsampling", false)
                .doesNotContainKey("seed");
    }
}
