package org.dual.replicate.app.chat.adapter.ai;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.dual.replicate.app.chat.domain.ChatAction;
import org.dual.replicate.app.generation.domain.Generation;
import org.dual.replicate.app.generation.domain.GenerationKind;
import org.dual.replicate.app.generation.domain.GenerationStatus;
import org.dual.replicate.app.generation.port.in.IGenerations;
import org.dual.replicate.app.generation.port.in.IModelCatalog;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ToolContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** I tool di proposta NON mutano nulla: solo letture, e un'azione depositata nell'holder del turno. */
class ActionProposalToolTest {

    private final IGenerations generations = mock(IGenerations.class);
    private final IModelCatalog modelCatalog = mock(IModelCatalog.class);
    private final ISystemEvents systemEvents = mock(ISystemEvents.class);
    private final ActionProposalTool tool = new ActionProposalTool(generations, modelCatalog, systemEvents);
    private final ActionProposalHolder holder = new ActionProposalHolder();
    private final ToolContext context = new ToolContext(Map.of(ActionProposalHolder.CONTEXT_KEY, holder));

    private Generation generation(boolean terminal) {
        Generation generation = mock(Generation.class);
        when(generation.isTerminal()).thenReturn(terminal);
        when(generation.getStatus()).thenReturn(terminal ? GenerationStatus.SUCCEEDED : GenerationStatus.PROCESSING);
        when(generation.getKind()).thenReturn(GenerationKind.IMAGE);
        when(generation.getModel()).thenReturn("owner/model");
        when(generation.getPrompt()).thenReturn("a cat");
        when(generation.getImageFilenames()).thenReturn(List.of("a.png"));
        when(generation.reusableSeedOf("a.png")).thenReturn(42L);
        when(generations.find(12L)).thenReturn(Optional.of(generation));
        return generation;
    }

    @Test
    void proposeCancelRegistersTheActionAndChangesNothing() {
        generation(false);

        String out = tool.proposeCancel(12L, context);

        assertThat(out).contains("Proposal prepared", "Nothing has been done yet");
        assertThat(holder.getActions()).containsExactly(ChatAction.cancel(12L));
        verify(generations).find(12L);
        verifyNoMoreInteractions(generations);
    }

    @Test
    void proposeCancelRefusesAGenerationThatIsAlreadyFinished() {
        generation(true);

        assertThat(tool.proposeCancel(12L, context)).contains("already finished");
        assertThat(holder.getActions()).isEmpty();
    }

    @Test
    void proposeDeleteRefusesAnUnknownGeneration() {
        when(generations.find(99L)).thenReturn(Optional.empty());

        assertThat(tool.proposeDelete(99L, context)).isEqualTo("No generation with id 99.");
        assertThat(holder.getActions()).isEmpty();
    }

    @Test
    void theSameProposalIsNotRegisteredTwice() {
        generation(false);

        tool.proposeDelete(12L, context);
        assertThat(tool.proposeDelete(12L, context)).contains("already made");
        assertThat(holder.getActions()).containsExactly(ChatAction.delete(12L));
    }

    @Test
    void proposeRegenerateCarriesTheFileAndTheModel() {
        generation(true);

        tool.proposeRegenerateWithSeed(12L, "a.png", context);

        assertThat(holder.getActions()).containsExactly(ChatAction.regenerate(12L, "a.png", "owner/model"));
    }

    @Test
    void proposeRegenerateRefusesEditModelsUnknownFilesAndFilesWithoutSeed() {
        Generation generation = generation(true);

        assertThat(tool.proposeRegenerateWithSeed(12L, "zzz.png", context)).contains("no file named");
        when(generation.getImageFilenames()).thenReturn(List.of("a.png", "b.png"));
        // Un mock restituirebbe 0L per un Long: il "nessun seed" va dichiarato.
        when(generation.reusableSeedOf("b.png")).thenReturn(null);
        assertThat(tool.proposeRegenerateWithSeed(12L, "b.png", context)).contains("no reproducible seed");
        when(modelCatalog.containsEdit("owner/model")).thenReturn(true);
        assertThat(tool.proposeRegenerateWithSeed(12L, "a.png", context)).contains("Only plain image");
        assertThat(holder.getActions()).isEmpty();
    }

    @Test
    void theHolderCapsTheNumberOfProposals() {
        for (long id = 1; id <= ActionProposalHolder.MAX_ACTIONS; id++) {
            assertThat(holder.add(ChatAction.delete(id))).isTrue();
        }
        assertThat(holder.add(ChatAction.delete(999L))).isFalse();
    }

    @Test
    void aFailureIsRecordedAndReturnedAsText() {
        RuntimeException boom = new IllegalStateException("db giu'");
        when(generations.find(12L)).thenThrow(boom);

        assertThat(tool.proposeDelete(12L, context)).contains("Could not prepare", "db giu'");
        verify(systemEvents).record(Mockito.eq("proposeDelete"), Mockito.eq(boom));
    }
}
