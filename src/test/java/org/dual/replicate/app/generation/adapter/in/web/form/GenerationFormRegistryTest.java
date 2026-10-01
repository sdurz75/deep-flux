package org.dual.replicate.app.generation.adapter.in.web.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.dual.replicate.app.generation.domain.GenerationFormType;
import org.dual.replicate.app.generation.port.in.ILoraPresets;
import org.dual.replicate.core.tokens.port.in.IApiTokens;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class GenerationFormRegistryTest {

    private final IApiTokens tokens = Mockito.mock(IApiTokens.class);
    private final ILoraPresets loraPresets = Mockito.mock(ILoraPresets.class);

    @Test
    void dispatchesToTheHandlerOfTheFormType() {
        var registry = new GenerationFormRegistry(List.of(new PVideoParameterHandler(), new FluxKreaDevParameterHandler()), tokens, loraPresets);

        assertThat(registry.handler(GenerationFormType.P_VIDEO)).isInstanceOf(PVideoParameterHandler.class);
        assertThat(registry.parameters(GenerationFormType.P_VIDEO, Map.of("duration", "7"))).containsEntry("duration", 7);
    }

    @Test
    void aFormTypeWithoutHandlerIsAnError() {
        var registry = new GenerationFormRegistry(List.of(new PVideoParameterHandler()), tokens, loraPresets);

        assertThatThrownBy(() -> registry.handler(GenerationFormType.FLUX_KREA_DEV)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void onlyFluxDevLoraGetsTheTokenAndLoraSelectsInItsFormModel() {
        Mockito.when(tokens.options("HUGGINGFACE")).thenReturn(List.of(
                new IApiTokens.TokenView(1L, "HUGGINGFACE", "Personale", "abcd", null, IApiTokens.Status.OK)));
        Mockito.when(tokens.options("CIVITAI")).thenReturn(List.of());
        Mockito.when(loraPresets.formOptions()).thenReturn(Map.of("loraPresets", List.of()));
        var registry = new GenerationFormRegistry(List.of(new PVideoParameterHandler(), new FluxDevLoraParameterHandler()), tokens, loraPresets);

        assertThat(registry.formModel(GenerationFormType.P_VIDEO)).containsEntry("duration", PVideoParameterHandler.DEFAULT_DURATION)
                .doesNotContainKeys("hfTokens", "civitaiTokens", "loraPresets");
        // Mai calcolate se il fragment non le usa.
        Mockito.verifyNoInteractions(tokens, loraPresets);
        assertThat(registry.formModel(GenerationFormType.FLUX_DEV_LORA)).containsKeys("guidance", "hfTokens", "civitaiTokens", "loraPresets");
        assertThat(registry.extraFormOptions(GenerationFormType.P_VIDEO)).isEmpty();
    }
}
