package org.dual.replicate.app.chat.adapter.ai;

import java.util.Map;
import java.util.Optional;

import org.dual.replicate.app.generation.domain.Generation;
import org.dual.replicate.app.generation.domain.GenerationKind;
import org.dual.replicate.app.generation.domain.ReplicateException;
import org.dual.replicate.app.generation.domain.ReplicateModel;
import org.dual.replicate.app.generation.port.in.IGenerations;
import org.dual.replicate.app.generation.port.in.IModelCatalog;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** L'unico tool a pagamento: modello sempre quello della UI, id restituito al modello, tetto per turno, errori come testo. */
class ImageGenerationToolTest {

    private final IGenerations generations = mock(IGenerations.class);
    private final IModelCatalog catalog = mock(IModelCatalog.class);
    private final ISystemEvents systemEvents = mock(ISystemEvents.class);
    private final ImageGenerationTool tool = new ImageGenerationTool(generations, catalog, systemEvents, 2);
    private final GenerationResultHolder holder = new GenerationResultHolder();

    private ToolContext context(String selectedModel) {
        Map<String, Object> context = new java.util.HashMap<>();
        context.put(GenerationResultHolder.CONTEXT_KEY, holder);
        context.put(ImageGenerationTool.PARAMETERS_CONTEXT_KEY, Map.of("num_outputs", 2));
        context.put(ImageGenerationTool.MODEL_CONTEXT_KEY, selectedModel);
        return new ToolContext(context);
    }

    private Generation started(long id) {
        Generation generation = new Generation("pred-" + id, "owner/model", null, "a cat", "{\"num_outputs\":2}");
        ReflectionTestUtils.setField(generation, "id", id);
        return generation;
    }

    @Test
    void startsWithTheModelOfTheUiReturnsTheIdAndRecordsItForTheWatcher() {
        when(catalog.contains("owner/model", GenerationKind.IMAGE)).thenReturn(true);
        when(catalog.versionOf("owner/model")).thenReturn(Optional.of("v1"));
        when(generations.create(any(IGenerations.CreateCommand.class))).thenReturn(started(12L));

        String out = tool.generateImage("a cat on a roof", context("owner/model"));

        assertThat(out).contains("Generation #12 started with model owner/model", "2 file(s) requested", "refer to it as #12");
        assertThat(holder.getStartedGenerationIds()).containsExactly(12L);
    }

    @Test
    void anUnknownModelFallsBackToTheCatalogDefaultNeverToAnArbitraryOne() {
        ReplicateModel fallback = mock(ReplicateModel.class);
        when(fallback.getIdentifier()).thenReturn("owner/default");
        when(catalog.contains("gone/model", GenerationKind.IMAGE)).thenReturn(false);
        when(catalog.defaultModel()).thenReturn(Optional.of(fallback));
        when(generations.create(any(IGenerations.CreateCommand.class))).thenReturn(started(3L));

        assertThat(tool.generateImage("a cat", context("gone/model"))).contains("model owner/default");
    }

    @Test
    void anEmptyPromptStartsNothing() {
        assertThat(tool.generateImage("   ", context("owner/model"))).contains("prompt is empty");
        verify(generations, never()).create(any(IGenerations.CreateCommand.class));
    }

    /** Ogni chiamata e' una predizione a pagamento: al tetto per turno il tool rifiuta senza chiamare Replicate. */
    @Test
    void thePerTurnLimitStopsFurtherGenerations() {
        when(catalog.contains("owner/model", GenerationKind.IMAGE)).thenReturn(true);
        when(generations.create(any(IGenerations.CreateCommand.class))).thenReturn(started(1L), started(2L));

        tool.generateImage("one", context("owner/model"));
        tool.generateImage("two", context("owner/model"));
        String third = tool.generateImage("three", context("owner/model"));

        assertThat(third).contains("Not started", "2 generations were already started");
        assertThat(holder.getStartedGenerationIds()).hasSize(2);
        verify(generations, org.mockito.Mockito.times(2)).create(any(IGenerations.CreateCommand.class));
    }

    @Test
    void aRejectedGenerationIsRecordedAndReturnedAsTextWithoutRetrying() {
        when(catalog.contains("owner/model", GenerationKind.IMAGE)).thenReturn(true);
        ReplicateException rejected = new ReplicateException("troppe generazioni in corso");
        when(generations.create(any(IGenerations.CreateCommand.class))).thenThrow(rejected);

        String out = tool.generateImage("a cat", context("owner/model"));

        assertThat(out).contains("Could not start the generation", "troppe generazioni in corso", "Do not retry");
        assertThat(holder.getStartedGenerationIds()).isEmpty();
        verify(systemEvents).record("createPrediction", rejected);
    }
}
