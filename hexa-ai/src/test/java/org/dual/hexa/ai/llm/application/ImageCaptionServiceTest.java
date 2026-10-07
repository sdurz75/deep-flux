package org.dual.hexa.ai.llm.application;

import org.dual.hexa.ai.llm.domain.CaptionStyle;
import org.dual.hexa.ai.llm.domain.ImageCaptionException;
import org.dual.hexa.ai.llm.port.out.IPromptModel;
import org.dual.hexa.ai.llm.port.out.ISourceImageScaler;
import org.dual.hexa.core.storage.domain.SourceImage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Il modello e' la porta {@link IPromptModel}: qui si prova la logica dello use case (guida per tipo, trigger word garantita, ripulitura, rifiuti). */
class ImageCaptionServiceTest {

    private final IPromptModel model = mock(IPromptModel.class);
    private final ISourceImageScaler scaler = mock(ISourceImageScaler.class);
    private final ImageCaptionService service = new ImageCaptionService(model, scaler, "guida-soggetto", "guida-stile", "vision", "fallback");
    private final SourceImage image = new SourceImage(new byte[]{1}, "image/png");

    @BeforeEach
    void scalerPassesTheImageThrough() {
        when(scaler.fitForVision(any(SourceImage.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void aSubjectIsCaptionedWithTheSubjectGuideAndTheTriggerWordInTheMessage() {
        when(model.complete(eq("captionTrainingImage"), eq("guida-soggetto"), contains("TOKCAT"), eq("vision"), eq(image)))
                .thenReturn("a photo of TOKCAT, sitting on a sofa");

        assertThat(service.caption(image, "TOKCAT", CaptionStyle.SUBJECT)).isEqualTo("a photo of TOKCAT, sitting on a sofa");
    }

    @Test
    void aStyleIsCaptionedWithTheStyleGuide() {
        when(model.complete(eq("captionTrainingImage"), eq("guida-stile"), anyString(), eq("vision"), eq(image)))
                .thenReturn("WTRCLR style, a lighthouse at dusk");

        assertThat(service.caption(image, "WTRCLR", CaptionStyle.STYLE)).isEqualTo("WTRCLR style, a lighthouse at dusk");
        verify(model, never()).complete(anyString(), eq("guida-soggetto"), anyString(), anyString(), any());
    }

    @Test
    void theTriggerWordIsPrependedWhenTheModelForgetsIt() {
        when(model.complete(anyString(), anyString(), anyString(), anyString(), any())).thenReturn("a cat sitting on a sofa");

        assertThat(service.caption(image, "TOKCAT", CaptionStyle.SUBJECT)).isEqualTo("TOKCAT, a cat sitting on a sofa");
    }

    @Test
    void aTriggerWordInAnyCaseCountsAsPresent() {
        when(model.complete(anyString(), anyString(), anyString(), anyString(), any())).thenReturn("a photo of tokcat on a sofa");

        assertThat(service.caption(image, "TOKCAT", CaptionStyle.SUBJECT)).isEqualTo("a photo of tokcat on a sofa");
    }

    @Test
    void labelsQuotesAndLineBreaksAreCleanedUpIntoASingleLine() {
        when(model.complete(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn("Caption: \"a photo of TOK,\n  a woman reading\non a bench\"");

        assertThat(service.caption(image, "TOK", CaptionStyle.SUBJECT)).isEqualTo("a photo of TOK, a woman reading on a bench");
    }

    @Test
    void aTooLongCaptionIsCutAtAWordAndKeepsTheTriggerWordAtTheFront() {
        String words = "word ".repeat(300);
        when(model.complete(anyString(), anyString(), anyString(), anyString(), any())).thenReturn(words);

        String caption = service.caption(image, "TOK", CaptionStyle.SUBJECT);

        assertThat(caption).startsWith("TOK, word").hasSizeLessThanOrEqualTo(ImageCaptionService.MAX_CAPTION).endsWith("word");
    }

    @Test
    void aRefusalFromBothModelsIsARejectedCaption() {
        when(model.complete(anyString(), anyString(), anyString(), anyString(), any())).thenReturn("I'm sorry, I can't help with that.");

        assertThatThrownBy(() -> service.caption(image, "TOK", CaptionStyle.SUBJECT)).isInstanceOf(ImageCaptionException.class);
    }

    @Test
    void anAnswerThatIsEmptyOnceCleanedIsRejected() {
        when(model.complete(anyString(), anyString(), anyString(), anyString(), any())).thenReturn("Caption: \"\"");

        assertThatThrownBy(() -> service.caption(image, "TOK", CaptionStyle.SUBJECT)).isInstanceOf(ImageCaptionException.class);
    }

    /** Come per l'"AI enhance": un errore o un rifiuto del modello principale scatena il fallback. */
    @Test
    void aFailureOfTheMainModelFallsBackToTheOtherOne() {
        when(model.complete(anyString(), anyString(), anyString(), eq("vision"), any())).thenThrow(new IllegalStateException("402"));
        when(model.complete(anyString(), anyString(), anyString(), eq("fallback"), any())).thenReturn("a photo of TOK, outdoors");

        assertThat(service.caption(image, "TOK", CaptionStyle.SUBJECT)).isEqualTo("a photo of TOK, outdoors");
    }

    @Test
    void aFailureOfBothModelsIsNotARefusalAndPropagatesAsAFailure() {
        // Due istanze: una per il modello principale e una per il fallback, come nella realta' (una stessa istanza non si puo' sopprimere in se' stessa).
        when(model.complete(anyString(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("giu'")).thenThrow(new IllegalStateException("giu' anche il fallback"));

        assertThatThrownBy(() -> service.caption(image, "TOK", CaptionStyle.SUBJECT)).isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(ImageCaptionException.class);
    }
}
