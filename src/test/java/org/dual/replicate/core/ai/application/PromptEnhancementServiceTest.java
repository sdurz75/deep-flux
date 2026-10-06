package org.dual.replicate.core.ai.application;

import org.dual.replicate.core.ai.domain.ImageScalingException;
import org.dual.replicate.core.ai.domain.PromptEnhancementRefusedException;
import org.dual.replicate.core.ai.port.out.IPromptModel;
import org.dual.replicate.core.ai.port.out.ISourceImageScaler;
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
    private final PromptEnhancementService service = new PromptEnhancementService(model, scaler, "guida", "video", "modifica", "inpaint", "img2img", "vision", "fallback");

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

    /** L'inpainting usa la SUA guida (solo il contenuto della zona), non quella generica text-to-image ne' quella di Kontext; l'immagine va al modello di visione. */
    @Test
    void enhanceInpaintUsesTheInpaintGuideAndLooksAtTheSourceImage() {
        SourceImage image = new SourceImage(new byte[]{1}, "image/png");
        when(model.complete(eq("enhanceVision"), eq("inpaint"), eq("sks, volto sorridente"), eq("vision"), eq(image)))
                .thenReturn("  sks, a smiling woman in her thirties looking at the camera  ");

        assertThat(service.enhanceInpaint("sks, volto sorridente", image))
                .isEqualTo("sks, a smiling woman in her thirties looking at the camera");
        verify(model, org.mockito.Mockito.never()).complete(anyString(), eq("guida"), anyString(), any(), any());
        verify(model, org.mockito.Mockito.never()).complete(anyString(), eq("modifica"), anyString(), any(), any());
    }

    /** L'img2img usa la SUA guida, guarda la sorgente e riceve la forza accodata alla bozza; senza forza la bozza passa com'e'. */
    @Test
    void enhanceImg2ImgUsesItsOwnGuideAndPassesTheStrengthToTheVisionModel() {
        SourceImage image = new SourceImage(new byte[]{1}, "image/png");
        when(model.complete(eq("enhanceVision"), eq("img2img"), eq("sks, in stile acquerello\n\n[prompt_strength: 0.35]"), eq("vision"), eq(image)))
                .thenReturn("  sks, rendered as a soft watercolour  ");
        when(model.complete(eq("enhanceVision"), eq("img2img"), eq("sks, in stile acquerello"), eq("vision"), eq(image)))
                .thenReturn("sks, a watercolour portrait");

        assertThat(service.enhanceImg2Img("sks, in stile acquerello", image, 0.35)).isEqualTo("sks, rendered as a soft watercolour");
        assertThat(service.enhanceImg2Img("sks, in stile acquerello", image, null)).isEqualTo("sks, a watercolour portrait");
        verify(model, org.mockito.Mockito.never()).complete(anyString(), eq("guida"), anyString(), any(), any());
        verify(model, org.mockito.Mockito.never()).complete(anyString(), eq("inpaint"), anyString(), any(), any());
    }

    /** Senza immagine (nessuna sorgente ancora) riscrive solo la bozza, sempre con la guida dell'inpainting; un rifiuto non sovrascrive la bozza. */
    @Test
    void enhanceInpaintWithoutAnImageRewritesTheDraftAndTreatsRefusalsAsRefusals() {
        when(model.complete(eq("enhance"), eq("inpaint"), eq("occhiali"), isNull(), isNull())).thenReturn("round tortoiseshell glasses");

        assertThat(service.enhanceInpaint("occhiali", null)).isEqualTo("round tortoiseshell glasses");

        when(model.complete(eq("enhance"), eq("inpaint"), eq("x"), isNull(), isNull())).thenReturn("I'm sorry, I can't help with that.");
        assertThatThrownBy(() -> service.enhanceInpaint("x", null)).isInstanceOf(PromptEnhancementRefusedException.class);
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
