package org.hexa.app.chat.adapter.ai;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.hexa.app.generation.domain.GalleryItem;
import org.hexa.app.generation.domain.Generation;
import org.hexa.app.generation.domain.GenerationKind;
import org.hexa.app.generation.port.in.IGenerations;
import org.hexa.app.generation.port.in.ILoraPresets;
import org.hexa.app.generation.port.in.IModelCatalog;
import org.hexa.core.events.domain.EventPage;
import org.hexa.core.events.domain.SystemEventSeverity;
import org.hexa.core.events.port.in.ISystemEvents;
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
        return new LibraryTool(modelCatalog, loraPresets, generations, systemEvents, new tools.jackson.databind.ObjectMapper());
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
    void listModelsAlsoNamesTheModelsThatNeedASourceImage() {
        org.hexa.app.generation.domain.ReplicateModel plain = mock(org.hexa.app.generation.domain.ReplicateModel.class);
        when(plain.getIdentifier()).thenReturn("owner/plain");
        org.hexa.app.generation.domain.ReplicateModel kontext = mock(org.hexa.app.generation.domain.ReplicateModel.class);
        when(kontext.getIdentifier()).thenReturn("owner/kontext");
        when(modelCatalog.models(GenerationKind.IMAGE)).thenReturn(List.of(plain));
        when(modelCatalog.formModels(GenerationKind.IMAGE)).thenReturn(List.of(plain, kontext));

        String out = tool().listModels(new ToolContext(Map.of()));

        assertThat(out).contains("- owner/plain").contains("need a source image").endsWith("owner/kontext").doesNotContain("- owner/kontext");
    }

    @Test
    void listTagsReturnsTheTagsInUse() {
        when(generations.allTags()).thenReturn(List.of("estate", "mare"));
        assertThat(tool().listTags()).isEqualTo("estate, mare");
        when(generations.allTags()).thenReturn(List.of());
        assertThat(tool().listTags()).isEqualTo("No tag is in use.");
    }

    /** Dettaglio: tag utente (generazione e file), sorgente, impostazioni a whitelist, MAI i parametri segreti o le sorgenti dei LoRA. */
    @Test
    void getGenerationShowsTagsSourceAndOnlyTheWhitelistedSettings() {
        Generation g = new Generation("pred", "owner/model", null, "a cat", "{\"aspect_ratio\":\"9:16\",\"num_outputs\":2,"
                + "\"lora_weights\":\"https://private.example/x.safetensors\",\"hf_token_id\":7}");
        org.springframework.test.util.ReflectionTestUtils.setField(g, "id", 5L);
        g.setImageFilenames(List.of("a.png", "b.png"));
        g.getTags().add("gatti");
        g.getFileTags().add(new org.hexa.app.generation.domain.FileTag("b.png", "rosso"));
        g.setSourceGenerationId(3L);
        when(generations.find(5L)).thenReturn(Optional.of(g));

        String out = tool().getGeneration(5L);

        assertThat(out).contains("Tags: gatti", "Started from an image of generation #3", "Settings: aspect_ratio=9:16, num_outputs=2",
                "- b.png [tags: rosso]").doesNotContain("private.example", "hf_token_id", "lora_weights");
    }

    @Test
    void getGenerationOfAnImportedImageShowsTheDescriptionAndKeepsAiKeywordsApartFromUserTags() {
        Generation g = Generation.imported("i.png", java.time.Instant.parse("2026-10-04T10:00:00Z"));
        org.springframework.test.util.ReflectionTestUtils.setField(g, "id", 8L);
        g.setImageFilenames(List.of("i.png"));
        g.applyAnalysis("una barca a vela", List.of("barca", "mare"));
        g.getTags().add("vacanze");
        when(generations.find(8L)).thenReturn(Optional.of(g));

        String out = tool().getGeneration(8L);

        assertThat(out).contains("imported image", "Description: una barca a vela", "Tags: vacanze", "Content analysis: DONE (AI keywords: barca, mare)")
                .doesNotContain("seed");
    }

    @Test
    void conversationGalleryShowsTagsAndTheGenerationsStillRunning() {
        Generation done = mock(Generation.class);
        when(done.getId()).thenReturn(12L);
        when(done.getPrompt()).thenReturn("a cat");
        when(done.getTags()).thenReturn(java.util.Set.of("gatti"));
        when(done.tagsOf("a.png")).thenReturn(List.of("rosso"));
        Generation running = mock(Generation.class);
        when(running.getId()).thenReturn(13L);
        when(running.getPrompt()).thenReturn("a dog");
        when(running.getStatus()).thenReturn(org.hexa.app.generation.domain.GenerationStatus.PROCESSING);
        when(generations.succeededItemsForConversation(7L)).thenReturn(List.of(new GalleryItem(done, "a.png")));
        when(generations.inProgressForConversation(7L)).thenReturn(List.of(running));

        String out = tool().conversationGallery(new ToolContext(Map.of(LibraryTool.CONVERSATION_ID_CONTEXT_KEY, 7L)));

        assertThat(out).isEqualTo("- #12 a.png [tags: gatti, rosso]: a cat\n- #13 (in progress, PROCESSING): a dog");
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
