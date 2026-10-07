package org.hexa.app.chat.adapter.ai;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.hexa.core.chat.domain.ChatConversation;
import org.hexa.core.chat.port.in.IChatConversations;
import org.hexa.app.generation.domain.Generation;
import org.hexa.app.generation.domain.ReplicateException;
import org.hexa.app.generation.port.in.IGenerations;
import org.hexa.core.events.port.in.ISystemEvents;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Cura dell'archivio: idempotente, su file veri, sulla SOLA conversazione corrente, rifiuti attesi come testo. */
class CurationToolTest {

    private final IGenerations generations = mock(IGenerations.class);
    private final IChatConversations conversations = mock(IChatConversations.class);
    private final ISystemEvents systemEvents = mock(ISystemEvents.class);
    private final CurationTool tool = new CurationTool(generations, conversations, systemEvents);

    private Generation generation(Set<String> favourites, Set<String> tags, List<String> fileATags) {
        Generation generation = mock(Generation.class);
        when(generation.getImageFilenames()).thenReturn(List.of("a.png", "b.png"));
        when(generation.getFavouriteFilenames()).thenReturn(favourites);
        when(generation.getTags()).thenReturn(tags);
        when(generation.tagsOf("a.png")).thenReturn(fileATags);
        when(generation.tagsOf("b.png")).thenReturn(List.of());
        when(generations.find(12L)).thenReturn(Optional.of(generation));
        return generation;
    }

    private ToolContext conversation(Long id) {
        return new ToolContext(id == null ? Map.of() : Map.of(LibraryTool.CONVERSATION_ID_CONTEXT_KEY, id));
    }

    @Test
    void setFavouriteTogglesOnlyWhenTheStateDiffers() {
        generation(Set.of("a.png"), Set.of(), List.of());

        tool.setFavourite(12L, "b.png", true);
        verify(generations).toggleFavourite(12L, "b.png");

        // Gia' con la star: richiederla di nuovo NON la toglie.
        assertThat(tool.setFavourite(12L, "a.png", true)).startsWith("Starred");
        verify(generations, never()).toggleFavourite(12L, "a.png");
    }

    @Test
    void setFavouriteRefusesUnknownGenerationsAndFiles() {
        generation(Set.of(), Set.of(), List.of());

        assertThat(tool.setFavourite(99L, "a.png", true)).contains("No generation");
        assertThat(tool.setFavourite(12L, "zzz.png", true)).contains("no file named");
        verify(generations, never()).toggleFavourite(any(), anyString());
    }

    @Test
    void setTagAddsToTheGenerationOrToOneFileAndNormalizesTheTag() {
        generation(Set.of(), Set.of("estate"), List.of());

        assertThat(tool.setTag(12L, null, "  Mare  Blu ", true)).isEqualTo("Tagged generation #12 with \"mare blu\".");
        verify(generations).addTag(12L, null, "mare blu");
        assertThat(tool.setTag(12L, "b.png", "rosso", true)).isEqualTo("Tagged b.png of generation #12 with \"rosso\".");
        verify(generations).addTag(12L, "b.png", "rosso");
        // Un file vuoto/bianco vale "tutta la generazione".
        tool.setTag(12L, "  ", "viaggio", true);
        verify(generations).addTag(12L, null, "viaggio");
    }

    @Test
    void setTagIsIdempotentInBothDirections() {
        generation(Set.of(), Set.of("estate"), List.of("rosso"));

        assertThat(tool.setTag(12L, null, "estate", true)).startsWith("Nothing to change").contains("already has");
        assertThat(tool.setTag(12L, "a.png", "rosso", true)).startsWith("Nothing to change");
        assertThat(tool.setTag(12L, null, "inverno", false)).startsWith("Nothing to change").contains("does not have");
        verify(generations, never()).addTag(any(), any(), anyString());
        verify(generations, never()).removeTag(any(), any(), anyString());

        assertThat(tool.setTag(12L, null, "Estate", false)).isEqualTo("Removed the tag \"estate\" from generation #12.");
        verify(generations).removeTag(12L, null, "estate");
    }

    @Test
    void setTagRefusesMissingInputsUnknownGenerationsAndFilesOfOthers() {
        generation(Set.of(), Set.of(), List.of());

        assertThat(tool.setTag(null, null, "x", true)).contains("Missing");
        assertThat(tool.setTag(12L, null, " , ", true)).contains("Missing");
        assertThat(tool.setTag(99L, null, "x", true)).contains("No generation");
        assertThat(tool.setTag(12L, "../etc/passwd", "x", true)).contains("no file named");
        verify(generations, never()).addTag(any(), any(), anyString());
    }

    /** Un rifiuto atteso del servizio (troppi tag) torna come testo e NON e' un evento di sistema. */
    @Test
    void anExpectedRefusalIsReturnedAsTextAndNotRecorded() {
        generation(Set.of(), Set.of(), List.of());
        org.mockito.Mockito.doThrow(new ReplicateException("Troppi tag")).when(generations).addTag(12L, null, "x");

        assertThat(tool.setTag(12L, null, "x", true)).isEqualTo("Not done: Troppi tag");
        verify(systemEvents, never()).record(anyString(), any(Throwable.class));
    }

    @Test
    void anUnexpectedFailureIsRecordedAndTheModelIsToldToReportIt() {
        generation(Set.of(), Set.of(), List.of());
        RuntimeException boom = new IllegalStateException("db giu'");
        org.mockito.Mockito.doThrow(boom).when(generations).addTag(12L, null, "x");

        assertThat(tool.setTag(12L, null, "x", true)).contains("internal error", "Tell the user");
        verify(systemEvents).record("setTag", boom);
    }

    @Test
    void conversationTagsActOnTheCurrentConversationOnly() {
        ChatConversation current = new ChatConversation();
        current.getTags().add("progetto");
        when(conversations.find(5L)).thenReturn(Optional.of(current));

        assertThat(tool.setConversationTag("Idee", true, conversation(5L))).isEqualTo("Tagged this conversation with \"idee\".");
        verify(conversations).addTag(5L, "idee");
        assertThat(tool.setConversationTag("progetto", true, conversation(5L))).startsWith("Nothing to change");
        assertThat(tool.setConversationTag("progetto", false, conversation(5L))).startsWith("Removed the tag");
        verify(conversations).removeTag(5L, "progetto");
        assertThat(tool.setConversationTag("idee", true, conversation(null))).contains("not available");
        assertThat(tool.setConversationTag("  ", true, conversation(5L))).contains("Missing tag");
    }

    @Test
    void renameConversationRenamesTheCurrentOneAndRefusesABlankTitle() {
        ChatConversation current = new ChatConversation();
        current.setTitle("Genera un gatto");
        when(conversations.find(5L)).thenReturn(Optional.of(current));
        when(conversations.rename(5L, " Gatti arancioni ")).thenAnswer(invocation -> {
            current.setTitle("Gatti arancioni");
            return current;
        });

        assertThat(tool.renameConversation(" Gatti arancioni ", conversation(5L))).isEqualTo("Renamed this conversation to \"Gatti arancioni\".");
        assertThat(tool.renameConversation("   ", conversation(5L))).contains("Missing title");
        assertThat(tool.renameConversation("x", conversation(null))).contains("not available");
        verify(conversations, org.mockito.Mockito.times(1)).rename(any(), anyString());
    }
}
