package org.dual.replicate.app.prompt.application;

import org.dual.replicate.app.prompt.domain.ImageScalingException;
import org.dual.replicate.app.prompt.domain.PromptEnhancementRefusedException;
import org.dual.replicate.app.prompt.port.out.IPromptModel;
import org.dual.replicate.app.prompt.port.out.ISourceImageScaler;
import org.dual.replicate.core.storage.domain.SourceImage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Il modello linguistico e' la porta {@link IPromptModel}: qui si prova solo la logica dello use case (guide, fallback, rifiuti). */
class PromptEnhancementServiceTest {

    private final IPromptModel model = mock(IPromptModel.class);
    private final ISourceImageScaler scaler = mock(ISourceImageScaler.class);
    private final PromptEnhancementService service = new PromptEnhancementService(model, scaler, "guida", "video", "modifica", "vision", "fallback");

    @BeforeEach
    void scalerPassesTheImageThrough() {
        when(scaler.fitForVision(any(SourceImage.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void theVisionModelReceivesTheScaledImageAndAScalingFailureNeverReachesIt() {
        SourceImage original = new SourceImage(new byte[]{1}, "image/png");
        SourceImage scaled = new SourceImage(new byte[]{2}, "image/jpeg");
        when(scaler.fitForVision(original)).thenReturn(scaled);
        when(model.complete(eq("enhanceVision"), anyString(), anyString(), eq("vision"), eq(scaled))).thenReturn("pan left");

        assertThat(service.enhanceVideo("", original)).isEqualTo("pan left");

        when(scaler.fitForVision(original)).thenThrow(new ImageScalingException("corrotta", new RuntimeException()));
        assertThatThrownBy(() -> service.enhanceVideo("", original)).isInstanceOf(ImageScalingException.class);
        verify(model, org.mockito.Mockito.times(1)).complete(eq("enhanceVision"), anyString(), anyString(), anyString(), any());
    }

    @Test
    void enhanceReturnsTrimmedModelOutputUsingTheImageGuideAndTheDefaultModel() {
        when(model.complete(eq("enhance"), eq("guida"), eq("gatto arancione"), isNull(), isNull()))
                .thenReturn("  a majestic orange cat sitting on a windowsill, soft morning light  ");

        assertThat(service.enhance("gatto arancione")).isEqualTo("a majestic orange cat sitting on a windowsill, soft morning light");
    }

    @Test
    void enhanceTreatsATextRefusalAsARefusalInsteadOfWritingItIntoTheDraft() {
        when(model.complete(anyString(), anyString(), anyString(), isNull(), isNull()))
                .thenReturn("I'm sorry, but I can't help with that request.");

        assertThatThrownBy(() -> service.enhance("gatto arancione")).isInstanceOf(PromptEnhancementRefusedException.class);
    }

    @Test
    void enhanceTreatsAnEmptyModelResponseAsARefusalInsteadOfOverwritingTheDraft() {
        when(model.complete(anyString(), anyString(), anyString(), isNull(), isNull())).thenReturn(null);

        // Una risposta vuota non deve sovrascrivere la bozza dell'utente: e' trattata come un rifiuto.
        assertThatThrownBy(() -> service.enhance("gatto arancione")).isInstanceOf(PromptEnhancementRefusedException.class);
    }

    @Test
    void isRefusalRecognisesTypicalRefusalsAndEmptyOutput() {
        assertThat(PromptEnhancementService.isRefusal("I'm sorry, I can't help with that.")).isTrue();
        assertThat(PromptEnhancementService.isRefusal("I cannot describe this image")).isTrue();
        assertThat(PromptEnhancementService.isRefusal("  ")).isTrue();
        assertThat(PromptEnhancementService.isRefusal(null)).isTrue();
        assertThat(PromptEnhancementService.isRefusal("The camera slowly dollies in as her hair sways in the breeze.")).isFalse();
    }

    @Test
    void enhanceVideoWithoutImageUsesTheTextModelWithTheVideoGuide() {
        when(model.complete(eq("enhance"), eq("video"), eq("stanza"), isNull(), isNull())).thenReturn(" slow pan across the room ");

        assertThat(service.enhanceVideo("stanza", null)).isEqualTo("slow pan across the room");
    }

    @Test
    void enhanceEditWithoutImageUsesTheTextModelWithTheEditGuide() {
        when(model.complete(eq("enhance"), eq("modifica"), eq("giacca rossa"), isNull(), isNull()))
                .thenReturn(" Change the jacket to red, keep the face unchanged ");

        assertThat(service.enhanceEdit("giacca rossa", null)).isEqualTo("Change the jacket to red, keep the face unchanged");
    }

    /** Il modello di visione rifiuta: si riprova UNA volta col fallback, e la risposta del fallback vale. */
    @Test
    void aVisionRefusalIsRetriedOnceWithTheFallbackModel() {
        SourceImage image = new SourceImage(new byte[]{1, 2, 3}, "image/png");
        when(model.complete(eq("enhanceVision"), eq("video"), anyString(), eq("vision"), any())).thenReturn("I'm sorry, I can't");
        when(model.complete(eq("enhanceVision"), eq("video"), anyString(), eq("fallback"), any())).thenReturn("slow zoom in");

        assertThat(service.enhanceVideo("", image)).isEqualTo("slow zoom in");
        verify(model).complete(eq("enhanceVision"), eq("video"), eq("Propose an animation prompt for this image."), eq("vision"), any());
    }

    /** Un errore del modello principale NON abortisce senza provare il fallback; se rifiuta anche quello, e' un rifiuto. */
    @Test
    void aVisionFailureFallsBackAndAFallbackRefusalIsReported() {
        SourceImage image = new SourceImage(new byte[]{1, 2, 3}, "image/png");
        when(model.complete(eq("enhanceVision"), anyString(), anyString(), eq("vision"), any())).thenThrow(new IllegalStateException("402"));
        when(model.complete(eq("enhanceVision"), anyString(), anyString(), eq("fallback"), any())).thenReturn("I cannot help");

        assertThatThrownBy(() -> service.enhanceEdit("giacca rossa", image)).isInstanceOf(PromptEnhancementRefusedException.class);
    }
}
