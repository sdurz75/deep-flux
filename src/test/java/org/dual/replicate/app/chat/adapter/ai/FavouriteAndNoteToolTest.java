package org.dual.replicate.app.chat.adapter.ai;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.dual.replicate.app.generation.domain.Generation;
import org.dual.replicate.app.generation.port.in.IGenerations;
import org.dual.replicate.app.search.domain.DocumentTypes;
import org.dual.replicate.app.search.port.in.IArchiveNotes;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FavouriteAndNoteToolTest {

    private final IGenerations generations = mock(IGenerations.class);
    private final IArchiveNotes notes = mock(IArchiveNotes.class);
    private final ISystemEvents systemEvents = mock(ISystemEvents.class);
    private final FavouriteTool favouriteTool = new FavouriteTool(generations, systemEvents);
    private final NoteTool noteTool = new NoteTool(notes, systemEvents);

    private Generation generation(Set<String> favourites) {
        Generation generation = mock(Generation.class);
        when(generation.getImageFilenames()).thenReturn(List.of("a.png", "b.png"));
        when(generation.getFavouriteFilenames()).thenReturn(favourites);
        when(generations.find(12L)).thenReturn(Optional.of(generation));
        return generation;
    }

    @Test
    void setFavouriteTogglesOnlyWhenTheStateDiffers() {
        generation(Set.of("a.png"));

        favouriteTool.setFavourite(12L, "b.png", true);
        verify(generations).toggleFavourite(12L, "b.png");

        // Gia' con la star: richiederla di nuovo NON la toglie.
        assertThat(favouriteTool.setFavourite(12L, "a.png", true)).startsWith("Starred");
        verify(generations, never()).toggleFavourite(12L, "a.png");
    }

    @Test
    void setFavouriteRefusesUnknownGenerationsAndFiles() {
        generation(Set.of());

        assertThat(favouriteTool.setFavourite(99L, "a.png", true)).contains("No generation");
        assertThat(favouriteTool.setFavourite(12L, "zzz.png", true)).contains("no file named");
        verify(generations, never()).toggleFavourite(any(), anyString());
    }

    @Test
    void saveNoteStoresTheTrimmedTextAndRejectsEmptyOrTooLongOnes() {
        assertThat(noteTool.saveNote(" Idea ", "  un castello  ")).isEqualTo("Note saved in the archive.");
        verify(notes).create("Idea", "un castello");

        assertThat(noteTool.saveNote("x", "   ")).contains("empty");
        assertThat(noteTool.saveNote("x", "a".repeat(DocumentTypes.MAX_CHARS + 1))).contains("too long");
        verify(notes, org.mockito.Mockito.times(1)).create(anyString(), anyString());
    }

    @Test
    void aFailureOnSaveIsRecordedAndReturnedAsText() {
        RuntimeException boom = new IllegalStateException("indice giu'");
        when(notes.create(anyString(), anyString())).thenThrow(boom);

        assertThat(noteTool.saveNote("t", "testo")).contains("Not saved", "indice giu'");
        verify(systemEvents).record("saveNote", boom);
    }
}
