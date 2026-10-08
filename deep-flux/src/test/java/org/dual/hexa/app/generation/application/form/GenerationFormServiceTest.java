package org.dual.hexa.app.generation.application.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.dual.hexa.app.generation.domain.GenerationFormType;
import org.dual.hexa.app.generation.port.in.ILoraPresets;
import org.dual.hexa.core.secrets.port.in.ISecrets;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class GenerationFormServiceTest {

    private final ISecrets secrets = Mockito.mock(ISecrets.class);
    private final ILoraPresets loraPresets = Mockito.mock(ILoraPresets.class);

    @Test
    void dispatchesToTheHandlerOfTheFormType() {
        var service = new GenerationFormService(List.of(new PVideoParameterHandler(), new FluxKreaDevParameterHandler()), secrets, loraPresets);

        assertThat(service.handler(GenerationFormType.P_VIDEO)).isInstanceOf(PVideoParameterHandler.class);
        assertThat(service.parameters(GenerationFormType.P_VIDEO, Map.of("duration", "7"))).containsEntry("duration", 7);
    }

    @Test
    void aFormTypeWithoutHandlerIsAnError() {
        var service = new GenerationFormService(List.of(new PVideoParameterHandler()), secrets, loraPresets);

        assertThatThrownBy(() -> service.handler(GenerationFormType.FLUX_KREA_DEV)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void onlyFluxDevLoraGetsTheTokenAndLoraSelectsInItsFormModel() {
        Mockito.when(secrets.options("HUGGINGFACE")).thenReturn(List.of(
                new ISecrets.SecretView(1L, "HUGGINGFACE", "Personale", "abcd", null, ISecrets.Status.OK, false)));
        Mockito.when(secrets.options("CIVITAI")).thenReturn(List.of());
        Mockito.when(loraPresets.formOptions()).thenReturn(Map.of("loraPresets", List.of()));
        var service = new GenerationFormService(List.of(new PVideoParameterHandler(), new FluxDevLoraParameterHandler()), secrets, loraPresets);

        assertThat(service.formModel(GenerationFormType.P_VIDEO)).containsEntry("duration", PVideoParameterHandler.DEFAULT_DURATION)
                .doesNotContainKeys("hfSecrets", "civitaiSecrets", "loraPresets");
        // Mai calcolate se il fragment non le usa.
        Mockito.verifyNoInteractions(secrets, loraPresets);
        assertThat(service.formModel(GenerationFormType.FLUX_DEV_LORA)).containsKeys("guidance", "hfSecrets", "civitaiSecrets", "loraPresets");
        assertThat(service.extraFormOptions(GenerationFormType.P_VIDEO)).isEmpty();
    }
}
