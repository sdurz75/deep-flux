package org.dual.replicate.app.chat.adapter.ai;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.dual.replicate.app.generation.domain.AnalysisStatus;
import org.dual.replicate.app.generation.domain.Generation;
import org.dual.replicate.app.generation.port.in.IGenerations;
import org.dual.replicate.app.prompt.domain.ImageAnalysisException;
import org.dual.replicate.app.prompt.domain.ImageDescription;
import org.dual.replicate.app.prompt.port.in.IImageDescriber;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.dual.replicate.core.storage.domain.SourceImage;
import org.dual.replicate.core.storage.domain.StorageException;
import org.dual.replicate.core.storage.port.in.IImageStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Vista sull'archivio: le importate analizzate rispondono dall'analisi salvata; il resto passa dal modello di visione, con un tetto per turno. */
class VisionToolTest {

    private final IGenerations generations = mock(IGenerations.class);
    private final IImageStorageService storage = mock(IImageStorageService.class);
    private final IImageDescriber describer = mock(IImageDescriber.class);
    private final ISystemEvents systemEvents = mock(ISystemEvents.class);
    private final VisionTool tool = new VisionTool(generations, storage, describer, systemEvents, 2);
    private final ToolContext context = new ToolContext(Map.of(VisionCallCounter.CONTEXT_KEY, new VisionCallCounter()));
    private final SourceImage image = new SourceImage(new byte[] {1, 2, 3}, "image/png");

    private Generation generation(boolean video, boolean imported, AnalysisStatus status) {
        Generation generation = mock(Generation.class);
        when(generation.getImageFilenames()).thenReturn(List.of("a.png"));
        when(generation.isVideo()).thenReturn(video);
        when(generation.isImported()).thenReturn(imported);
        when(generation.getAnalysisStatus()).thenReturn(status);
        when(generation.getPrompt()).thenReturn("una barca a vela");
        when(generation.getAnalysisTagList()).thenReturn(List.of("barca", "mare"));
        when(generations.find(12L)).thenReturn(Optional.of(generation));
        return generation;
    }

    @Test
    void anAnalysedImportedImageIsAnsweredFromTheStoredAnalysisWithoutAnyModelCall() {
        generation(false, true, AnalysisStatus.DONE);

        String out = tool.describeImage(12L, "a.png", context);

        assertThat(out).contains("no model call", "Description: una barca a vela", "Keywords: barca, mare");
        verify(describer, never()).describe(any());
        verify(storage, never()).read(any());
    }

    @Test
    void aGeneratedImageIsSentToTheVisionModel() {
        generation(false, false, null);
        when(storage.read("a.png")).thenReturn(image);
        when(describer.describe(image)).thenReturn(new ImageDescription("un gatto rosso su un tetto", List.of("gatto", "tetto")));

        assertThat(tool.describeImage(12L, "a.png", context)).contains("Description: un gatto rosso su un tetto", "Keywords: gatto, tetto");
    }

    @Test
    void anImportedImageWhoseAnalysisFailedIsLookedAtNow() {
        generation(false, true, AnalysisStatus.FAILED);
        when(storage.read("a.png")).thenReturn(image);
        when(describer.describe(image)).thenReturn(new ImageDescription("una barca", List.of()));

        assertThat(tool.describeImage(12L, "a.png", context)).contains("Description: una barca").doesNotContain("Keywords");
    }

    @Test
    void videosUnknownGenerationsAndForeignFilesAreRefusedWithoutLooking() {
        generation(true, false, null);

        assertThat(tool.describeImage(12L, "a.png", context)).contains("video");
        assertThat(tool.describeImage(99L, "a.png", context)).contains("No generation");
        assertThat(tool.describeImage(12L, "zzz.png", context)).contains("no file named");
        assertThat(tool.describeImage(null, "a.png", context)).contains("Missing");
        verify(describer, never()).describe(any());
    }

    /** Ogni chiamata costa token: oltre il tetto per turno il modello non viene interpellato. */
    @Test
    void thePerTurnLimitStopsFurtherVisionCalls() {
        generation(false, false, null);
        when(storage.read("a.png")).thenReturn(image);
        when(describer.describe(image)).thenReturn(new ImageDescription("x", List.of()));

        tool.describeImage(12L, "a.png", context);
        tool.describeImage(12L, "a.png", context);
        String third = tool.describeImage(12L, "a.png", context);

        assertThat(third).contains("limit of 2", "next message");
        verify(describer, org.mockito.Mockito.times(2)).describe(image);
    }

    @Test
    void aRefusalOfTheVisionModelIsAnExpectedOutcomeNotAnError() {
        generation(false, false, null);
        when(storage.read("a.png")).thenReturn(image);
        when(describer.describe(image)).thenThrow(new ImageAnalysisException("rifiutato", null));

        assertThat(tool.describeImage(12L, "a.png", context)).contains("declined", "Do not retry");
        verify(systemEvents, never()).record(org.mockito.ArgumentMatchers.anyString(), any(Throwable.class));
    }

    @Test
    void aServiceFailureIsRecordedAndTheModelIsToldNotToRetry() {
        generation(false, false, null);
        RuntimeException boom = new IllegalStateException("openrouter giu'");
        when(storage.read("a.png")).thenReturn(image);
        when(describer.describe(image)).thenThrow(boom);

        assertThat(tool.describeImage(12L, "a.png", context)).contains("Could not look at the image", "Do not retry");
        verify(systemEvents).record("describeImage", boom);
    }

    @Test
    void withoutACounterInTheContextNothingIsSentToTheModel() {
        generation(false, false, null);

        assertThat(tool.describeImage(12L, "a.png", new ToolContext(Map.of()))).contains("not available");
        verify(describer, never()).describe(any());
    }
}
