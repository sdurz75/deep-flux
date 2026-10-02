package org.dual.replicate.app.chat.adapter.ai;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.dual.replicate.app.generation.domain.GalleryItem;
import org.dual.replicate.app.generation.domain.Generation;
import org.dual.replicate.app.generation.domain.GenerationKind;
import org.dual.replicate.app.generation.port.in.IGenerations;
import org.dual.replicate.app.generation.port.in.ILoraPresets;
import org.dual.replicate.app.generation.port.in.IModelCatalog;
import org.dual.replicate.core.events.domain.EventPage;
import org.dual.replicate.core.events.domain.SystemEventSeverity;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LibraryToolTest {

    @Mock
    private IModelCatalog modelCatalog;
    @Mock
    private ILoraPresets loraPresets;
    @Mock
    private IGenerations generations;
    @Mock
    private ISystemEvents systemEvents;

    private LibraryTool tool() {
        return new LibraryTool(modelCatalog, loraPresets, generations, systemEvents);
    }

    /** I LoRA escono con nome, scala e trigger words, MAI con la sorgente (potrebbe essere un URL privato). */
    @Test
    void listLoraPresetsExposesTriggerWordsButNotTheSource() {
        when(loraPresets.list()).thenReturn(List.of(
                new ILoraPresets.LoraView(1L, "Mio stile", "https://private.example/secret.safetensors", 0.8, "sks style", "nota")));

        String out = tool().listLoraPresets();

        assertThat(out).contains("Mio stile", "0.8", "sks style", "nota").doesNotContain("private.example");
    }

    @Test
    void getGenerationReportsAnUnknownId() {
        when(generations.find(99L)).thenReturn(Optional.empty());

        assertThat(tool().getGeneration(99L)).isEqualTo("No generation with id 99.");
    }

    @Test
    void conversationGalleryNeedsTheConversationFromTheToolContext() {
        assertThat(tool().conversationGallery(new ToolContext(Map.of()))).contains("unknown");
    }

    @Test
    void conversationGalleryListsTheItemsOfTheCurrentConversation() {
        Generation generation = mock(Generation.class);
        when(generation.getId()).thenReturn(12L);
        when(generation.getPrompt()).thenReturn("a cat\non a roof");
        when(generation.getFavouriteFilenames()).thenReturn(java.util.Set.of("a.png"));
        when(generations.succeededItemsForConversation(7L)).thenReturn(List.of(new GalleryItem(generation, "a.png")));

        String out = tool().conversationGallery(new ToolContext(Map.of(LibraryTool.CONVERSATION_ID_CONTEXT_KEY, 7L)));

        assertThat(out).isEqualTo("- #12 a.png (favourite): a cat on a roof");
    }

    @Test
    void recentEventsRejectsAnUnknownSeverityWithoutQueryingTheRegistry() {
        assertThat(tool().recentEvents("fatal")).contains("Invalid severity");
    }

    @Test
    void recentEventsQueriesTheRegistryWithTheRequestedSeverity() {
        when(systemEvents.list(SystemEventSeverity.ERROR, 0, 10)).thenReturn(new EventPage(List.of(), 0, false, false));

        assertThat(tool().recentEvents("error")).isEqualTo("No recent events.");
    }

    /** Un guasto non rompe il turno: e' registrato e il modello riceve un testo. */
    @Test
    void aFailureIsRecordedAndReturnedAsText() {
        RuntimeException boom = new IllegalStateException("db giu'");
        when(modelCatalog.models(GenerationKind.IMAGE)).thenThrow(boom);

        String out = tool().listModels(new ToolContext(Map.of()));

        assertThat(out).contains("not available", "db giu'");
        verify(systemEvents).record(eq("listModels"), eq(boom));
    }
}
