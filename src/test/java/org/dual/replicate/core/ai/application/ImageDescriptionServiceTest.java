package org.dual.replicate.core.ai.application;

import org.dual.replicate.core.ai.domain.ImageAnalysisException;
import org.dual.replicate.core.ai.domain.ImageDescription;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Il modello e' la porta {@link IPromptModel}: qui si prova solo la logica dello use case (formato, fallback, rifiuti). */
class ImageDescriptionServiceTest {

    private final IPromptModel model = mock(IPromptModel.class);
    private final ISourceImageScaler scaler = mock(ISourceImageScaler.class);
    private final ImageDescriptionService service = new ImageDescriptionService(model, scaler, "guida", "vision", "fallback");
    private final SourceImage image = new SourceImage(new byte[]{1}, "image/png");

    @BeforeEach
    void scalerPassesTheImageThrough() {
        when(scaler.fitForVision(any(SourceImage.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void readsDescriptionAndTagsFromTheFixedFormat() {
        when(model.complete(eq("describeImage"), eq("guida"), anyString(), eq("vision"), eq(image)))
                .thenReturn("DESCRIPTION: Un gatto rosso su un divano.\nTAGS: gatto, cat, divano, sofa, gatto, - rosso");

        ImageDescription result = service.describe(image);

        assertThat(result.description()).isEqualTo("Un gatto rosso su un divano.");
        assertThat(result.tags()).containsExactly("gatto", "cat", "divano", "sofa", "rosso");
    }

    @Test
    void aMultilineDescriptionAndMissingTagsAreAccepted() {
        when(model.complete(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn("DESCRIPTION: Prima riga.\nSeconda riga.");

        ImageDescription result = service.describe(image);

        assertThat(result.description()).isEqualTo("Prima riga.\nSeconda riga.");
        assertThat(result.tags()).isEmpty();
    }

    @Test
    void anUnreadableAnswerIsARejectedAnalysis() {
        when(model.complete(anyString(), anyString(), anyString(), anyString(), any())).thenReturn("Ecco un'immagine bellissima!");

        assertThatThrownBy(() -> service.describe(image)).isInstanceOf(ImageAnalysisException.class);
    }

    @Test
    void anEmptyDescriptionIsRejected() {
        when(model.complete(anyString(), anyString(), anyString(), anyString(), any())).thenReturn("DESCRIPTION:   \nTAGS: a");

        assertThatThrownBy(() -> service.describe(image)).isInstanceOf(ImageAnalysisException.class);
    }

    /** Come per l'"AI enhance": un errore o un rifiuto del modello principale scatena il fallback. */
    @Test
    void theFallbackModelIsUsedWhenThePrimaryFailsOrRefuses() {
        when(model.complete(anyString(), anyString(), anyString(), eq("vision"), any())).thenThrow(new IllegalStateException("402"));
        when(model.complete(anyString(), anyString(), anyString(), eq("fallback"), any())).thenReturn("DESCRIPTION: ok\nTAGS: a");

        assertThat(service.describe(image).description()).isEqualTo("ok");

        when(model.complete(anyString(), anyString(), anyString(), eq("vision"), any())).thenReturn("I'm sorry, I can't help with that.");
        assertThat(service.describe(image).description()).isEqualTo("ok");
    }

    @Test
    void whenBothModelsRefuseTheAnalysisIsRejected() {
        when(model.complete(anyString(), anyString(), anyString(), anyString(), any())).thenReturn("I'm sorry, I can't describe this.");

        assertThatThrownBy(() -> service.describe(image)).isInstanceOf(ImageAnalysisException.class);
    }

    @Test
    void theDescriptionIsCappedAndTagsLimited() {
        String longText = "x".repeat(ImageDescriptionService.MAX_DESCRIPTION + 500);
        StringBuilder tags = new StringBuilder();
        for (int i = 0; i < ImageDescriptionService.MAX_TAGS + 10; i++) {
            tags.append("t").append(i).append(", ");
        }
        when(model.complete(anyString(), anyString(), anyString(), anyString(), any())).thenReturn("DESCRIPTION: " + longText + "\nTAGS: " + tags);

        ImageDescription result = service.describe(image);

        assertThat(result.description()).hasSize(ImageDescriptionService.MAX_DESCRIPTION);
        assertThat(result.tags()).hasSize(ImageDescriptionService.MAX_TAGS);
        verify(model, never()).complete(anyString(), anyString(), anyString(), eq("fallback"), any());
    }
}
