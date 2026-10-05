package org.dual.replicate.app.chat.adapter.ai;

import java.util.List;
import java.util.Map;

import org.dual.replicate.app.search.domain.DocumentFilter;
import org.dual.replicate.app.search.domain.ScoredDocument;
import org.dual.replicate.app.search.domain.SearchableDocument;
import org.dual.replicate.app.search.port.in.IArchiveSearch;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArchiveSearchToolTest {

    private final IArchiveSearch search = mock(IArchiveSearch.class);
    private final ISystemEvents systemEvents = mock(ISystemEvents.class);
    private final ArchiveSearchTool tool = new ArchiveSearchTool(search, systemEvents, 3);

    private static ScoredDocument hit(String id, String text, Map<String, Object> metadata) {
        return new ScoredDocument(new SearchableDocument(id, text, metadata), 0.9);
    }

    @Test
    void describesEachKindOfHitWithItsIdAndLink() {
        when(search.search(anyString(), any(DocumentFilter.class), anyDouble(), anyInt())).thenReturn(List.of(
                hit("generation:12", "un felino sul divano", Map.of("type", "generation", "refId", 12)),
                hit("chatmessage:5", "vorrei un castello", Map.of("type", "chat", "refId", 5, "conversationId", 3, "role", "USER")),
                hit("conversation:3", "Il castello del drago", Map.of("type", "conversation", "refId", 3))));

        String result = tool.searchArchive("gatto", null, null, null, null, null, null);

        assertThat(result).contains("[generation #12] (/generations/12) un felino sul divano")
                .contains("[chat, conversation #3, USER] vorrei un castello")
                .contains("[conversation #3] Il castello del drago");
        ArgumentCaptor<DocumentFilter> filter = ArgumentCaptor.forClass(DocumentFilter.class);
        verify(search).search(org.mockito.ArgumentMatchers.eq("gatto"), filter.capture(), anyDouble(), org.mockito.ArgumentMatchers.eq(3));
        assertThat(filter.getValue().type()).isNull();
    }

    @Test
    void aTypeFilterIsAppliedAndAnInvalidOneIsRefusedWithoutSearching() {
        when(search.search(anyString(), any(DocumentFilter.class), anyDouble(), anyInt())).thenReturn(List.of());

        assertThat(tool.searchArchive("gatto", "Generation", null, null, null, null, null)).isEqualTo("No results in the archive.");
        ArgumentCaptor<DocumentFilter> filter = ArgumentCaptor.forClass(DocumentFilter.class);
        verify(search).search(anyString(), filter.capture(), anyDouble(), anyInt());
        assertThat(filter.getValue().type()).isEqualTo("generation");

        org.mockito.Mockito.clearInvocations(search);
        assertThat(tool.searchArchive("gatto", "immagini", null, null, null, null, null)).contains("Invalid type");
        verify(search, never()).search(anyString(), any(DocumentFilter.class), anyDouble(), anyInt());
    }

    @Test
    void aTagFilterIsNormalizedAndShownInTheResultsAndAnEmptyQueryJustListsTheTaggedItems() {
        when(search.search(anyString(), any(DocumentFilter.class), anyDouble(), anyInt())).thenReturn(List.of(
                hit("imported:7", "una barca", Map.of("type", "imported", "refId", 7, "tags", List.of("mare", "vacanze")))));

        String result = tool.searchArchive("barca", null, "  Mare ", null, null, null, null);

        assertThat(result).contains("[imported image #7] (/import/7) [tags: mare, vacanze] una barca");
        ArgumentCaptor<DocumentFilter> filter = ArgumentCaptor.forClass(DocumentFilter.class);
        verify(search).search(anyString(), filter.capture(), anyDouble(), anyInt());
        assertThat(filter.getValue().tag()).isEqualTo("mare");

        // senza testo ma con il tag: nessuna ricerca per significato, l'elenco dei piu' recenti col tag
        when(search.list(any(DocumentFilter.class), org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.eq(3)))
                .thenReturn(new org.dual.replicate.core.kernel.Paged<>(List.of(new org.dual.replicate.app.search.domain.IndexedDocument(
                        "generation:2", "generation", 2L, null, "un faro", Map.of("type", "generation", "refId", 2, "tags", List.of("mare")),
                        "m", "h", java.time.Instant.EPOCH, 384)), 0, 3, 1));
        org.mockito.Mockito.clearInvocations(search);
        assertThat(tool.searchArchive("", null, "mare", null, null, null, null)).contains("[generation #2] (/generations/2) [tags: mare] un faro");
        verify(search, never()).search(anyString(), any(DocumentFilter.class), anyDouble(), anyInt());
    }

    @Test
    void notesAreSearchableAndDescribedByTitleNotByTheirTimestampRefId() {
        when(search.search(anyString(), any(DocumentFilter.class), anyDouble(), anyInt())).thenReturn(List.of(
                hit("note:abc", "la trigger word e' sks", Map.of("type", "note", "refId", 1_700_000_000_000L, "title", "LoRA mio"))));

        String result = tool.searchArchive("trigger", "Note", null, null, null, null, null);

        assertThat(result).isEqualTo("- [note \"LoRA mio\"] (note:abc) la trigger word e' sks");
        ArgumentCaptor<DocumentFilter> filter = ArgumentCaptor.forClass(DocumentFilter.class);
        verify(search).search(anyString(), filter.capture(), anyDouble(), anyInt());
        assertThat(filter.getValue().type()).isEqualTo("note");
    }

    @Test
    void mediaFavouritesAndDatesFillTheFilterAndEndOfDayIsInclusive() {
        when(search.search(anyString(), any(DocumentFilter.class), anyDouble(), anyInt())).thenReturn(List.of());

        tool.searchArchive("faro", null, null, true, "Video", "2026-01-01", "2026-01-31");

        ArgumentCaptor<DocumentFilter> filter = ArgumentCaptor.forClass(DocumentFilter.class);
        verify(search).search(anyString(), filter.capture(), anyDouble(), anyInt());
        assertThat(filter.getValue().kind()).isEqualTo("VIDEO");
        assertThat(filter.getValue().favouriteOnly()).isTrue();
        assertThat(filter.getValue().from()).isEqualTo(java.time.LocalDate.of(2026, 1, 1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant());
        assertThat(filter.getValue().to()).isEqualTo(java.time.LocalDate.of(2026, 2, 1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().minusMillis(1));
    }

    @Test
    void anEmptyQueryNeedsAFilterAndInvalidMediaOrDatesAreRefusedWithoutSearching() {
        assertThat(tool.searchArchive("  ", null, null, null, null, null, null)).contains("at least one filter");
        assertThat(tool.searchArchive("faro", null, null, null, "audio", null, null)).contains("Invalid media");
        assertThat(tool.searchArchive("faro", null, null, null, null, "ieri", null)).contains("Invalid date");
        assertThat(tool.searchArchive(null, null, null, false, null, null, null)).contains("at least one filter");

        verify(search, never()).search(anyString(), any(DocumentFilter.class), anyDouble(), anyInt());
        verify(search, never()).list(any(DocumentFilter.class), anyInt(), anyInt());
    }

    @Test
    void aStoreFailureIsRecordedAndReportedToTheModelInsteadOfBreakingTheTurn() {
        RuntimeException failure = new IllegalStateException("modello non caricato");
        when(search.search(anyString(), any(DocumentFilter.class), anyDouble(), anyInt())).thenThrow(failure);

        String result = tool.searchArchive("gatto", null, null, null, null, null, null);

        assertThat(result).contains("not available").contains("modello non caricato");
        verify(systemEvents).record("searchArchive", failure);
    }
}
