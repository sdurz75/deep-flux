package org.dual.hexa.app.chat.adapter.ai;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.dual.hexa.ai.credits.domain.CreditLine;
import org.dual.hexa.ai.credits.port.in.ICredits;
import org.dual.hexa.app.generation.domain.GalleryItem;
import org.dual.hexa.app.generation.domain.Generation;
import org.dual.hexa.app.generation.port.in.IGenerations;
import org.dual.hexa.core.events.port.in.ISystemEvents;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Sola lettura: crediti come nella barra (Replicate stimato) e costo stimato della conversazione corrente. */
class CreditsToolTest {

    private final ICredits credits = mock(ICredits.class);
    private final IGenerations generations = mock(IGenerations.class);
    private final ISystemEvents systemEvents = mock(ISystemEvents.class);
    private final CreditsTool tool = new CreditsTool(credits, generations, systemEvents);

    private static ToolContext conversation(long id) {
        return new ToolContext(Map.of(LibraryTool.CONVERSATION_ID_CONTEXT_KEY, id));
    }

    private static Generation generation(long id, String cost) {
        Generation generation = mock(Generation.class);
        when(generation.getId()).thenReturn(id);
        when(generation.getCostUsd()).thenReturn(cost == null ? null : new BigDecimal(cost));
        return generation;
    }

    @Test
    void showsEachServiceMarkingTheReplicateOneAsAnEstimateAndLowBalances() {
        when(credits.lines()).thenReturn(List.of(
                CreditLine.ok("REPLICATE", new BigDecimal("1.5"), Instant.now(), true),
                CreditLine.ok(CreditLine.OPENROUTER, new BigDecimal("12.3456"), Instant.now(), false)));

        String out = tool.getCredits(new ToolContext(Map.of()));

        assertThat(out).contains("Replicate: ~$1.50 (estimate", "LOW, below $2").contains("OpenRouter: $12.35").doesNotContain("This conversation");
    }

    @Test
    void unsetAndUnavailableServicesAreSaidPlainly() {
        when(credits.lines()).thenReturn(List.of(CreditLine.notSet("REPLICATE", true), CreditLine.unavailable(CreditLine.OPENROUTER, false)));

        assertThat(tool.getCredits(new ToolContext(Map.of()))).contains("Replicate: no balance entered yet", "OpenRouter: temporarily unavailable");
    }

    /** Un file per riga di galleria, ma una generazione conta una volta; senza costo noto non conta. */
    @Test
    void theConversationCostSumsEachGenerationOnceAndSkipsTheOnesWithoutACost() {
        when(credits.lines()).thenReturn(List.of());
        Generation twoFiles = generation(12, "0.040");
        Generation other = generation(13, "0.0125");
        Generation unknown = generation(14, null);
        when(generations.succeededItemsForConversation(7L)).thenReturn(List.of(
                new GalleryItem(twoFiles, "a.png"), new GalleryItem(twoFiles, "b.png"), new GalleryItem(other, "c.png"), new GalleryItem(unknown, "d.png")));

        assertThat(tool.getCredits(conversation(7L))).isEqualTo(
                "No credit information is available.\nThis conversation: ~$0.05 estimated for 2 succeeded generation(s) with a known cost.");
    }

    @Test
    void aConversationWithoutCostsSaysSo() {
        when(credits.lines()).thenReturn(List.of());
        when(generations.succeededItemsForConversation(7L)).thenReturn(List.of());

        assertThat(tool.getCredits(conversation(7L))).endsWith("This conversation: no estimated cost recorded yet.");
    }

    @Test
    void aFailureIsRecordedAndReported() {
        RuntimeException boom = new IllegalStateException("giu'");
        when(credits.lines()).thenThrow(boom);

        assertThat(tool.getCredits(new ToolContext(Map.of()))).contains("Could not read the credits", "Tell the user");
        verify(systemEvents).record("getCredits", boom);
        verifyNoMoreInteractions(generations);
    }
}
