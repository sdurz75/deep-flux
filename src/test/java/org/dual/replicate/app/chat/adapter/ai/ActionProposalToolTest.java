package org.dual.replicate.app.chat.adapter.ai;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.dual.replicate.app.chat.domain.ChatAction;
import org.dual.replicate.app.generation.domain.Generation;
import org.dual.replicate.app.generation.domain.GenerationKind;
import org.dual.replicate.app.generation.domain.GenerationStatus;
import org.dual.replicate.app.generation.port.in.IGenerations;
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
    private final ISystemEvents systemEvents = mock(ISystemEvents.class);
    private final ActionProposalTool tool = new ActionProposalTool(generations, systemEvents);
    private final ActionProposalHolder holder = new ActionProposalHolder();
    private final ToolContext context = new ToolContext(Map.of(ActionProposalHolder.CONTEXT_KEY, holder));

    private Generation generation(boolean terminal) {
        Generation generation = mock(Generation.class);
        when(generation.isTerminal()).thenReturn(terminal);
        when(generation.getStatus()).thenReturn(terminal ? GenerationStatus.SUCCEEDED : GenerationStatus.PROCESSING);
        when(generation.getKind()).thenReturn(GenerationKind.IMAGE);
        when(generation.getModel()).thenReturn("owner/model");
        when(generation.getPrompt()).thenReturn("a cat");
        // Un mock restituirebbe 0L per un Long: "nessuna sorgente da generazione" va dichiarato.
        when(generation.getSourceGenerationId()).thenReturn(null);
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
    void proposeAnimateAndUseAsSourceNeedASucceededImageFileOfThatGeneration() {
        generation(true);
        when(generations.findAnimatableSource(12L, "a.png")).thenReturn(Optional.of(mock(Generation.class)));
        when(generations.findAnimatableSource(12L, "clip.mp4")).thenReturn(Optional.empty());

        assertThat(tool.proposeAnimate(12L, "a.png", context)).contains("Proposal prepared", "Nothing has been done yet");
        assertThat(tool.proposeUseAsSource(12L, "a.png", context)).contains("Proposal prepared");
        assertThat(holder.getActions()).containsExactly(ChatAction.animate(12L, "a.png"), ChatAction.useAsSource(12L, "a.png"));

        assertThat(tool.proposeAnimate(12L, "clip.mp4", context)).contains("no succeeded image file named clip.mp4");
        assertThat(tool.proposeUseAsSource(12L, null, context)).contains("no succeeded image file");
        assertThat(holder.getActions()).hasSize(2);
    }

    @Test
    void proposeDeleteFileOnlyForAFileOfThatGeneration() {
        generation(true);

        assertThat(tool.proposeDeleteFile(12L, "a.png", context)).contains("Proposal prepared", "Nothing has been done yet");
        assertThat(holder.getActions()).containsExactly(ChatAction.deleteFile(12L, "a.png"));
        assertThat(tool.proposeDeleteFile(12L, "zzz.png", context)).contains("no file named zzz.png");
        assertThat(tool.proposeDeleteFile(12L, null, context)).contains("no file named");
        assertThat(holder.getActions()).hasSize(1);
        // Proporre due volte lo stesso file nel turno non ripete il bottone.
        assertThat(tool.proposeDeleteFile(12L, "a.png", context)).contains("already made");
    }

    @Test
    void proposeRegenerateRefusesAnImportedImageWithAClearReason() {
        Generation imported = generation(true);
        when(imported.isImported()).thenReturn(true);

        assertThat(tool.proposeRegenerateWithSeed(12L, "a.png", context)).contains("imported image", "no model, prompt or seed");
        assertThat(holder.getActions()).isEmpty();
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
    void proposeRegenerateRefusesUnknownFilesAndFilesWithoutSeed() {
        Generation generation = generation(true);

        assertThat(tool.proposeRegenerateWithSeed(12L, "zzz.png", context)).contains("no file named");
        when(generation.getImageFilenames()).thenReturn(List.of("a.png", "b.png"));
        // Un mock restituirebbe 0L per un Long: il "nessun seed" va dichiarato.
        when(generation.reusableSeedOf("b.png")).thenReturn(null);
        assertThat(tool.proposeRegenerateWithSeed(12L, "b.png", context)).contains("no reproducible seed");
        assertThat(holder.getActions()).isEmpty();
    }

    @Test
    void proposeRegenerateRefusesAGenerationStartedFromAnUploadOrAMask() {
        Generation generation = generation(true);
        when(generation.getSourceUploadFilename()).thenReturn("up.png");
        assertThat(tool.proposeRegenerateWithSeed(12L, "a.png", context)).contains("uploaded image or mask");

        when(generation.getSourceUploadFilename()).thenReturn(null);
        when(generation.getMaskUploadFilename()).thenReturn("mask.png");
        assertThat(tool.proposeRegenerateWithSeed(12L, "a.png", context)).contains("uploaded image or mask");
        assertThat(holder.getActions()).isEmpty();
    }

    @Test
    void proposeRegenerateChecksThatTheSourceGenerationStillExists() {
        Generation generation = generation(true);
        when(generation.getSourceGenerationId()).thenReturn(7L);
        when(generation.getSourceImageFilename()).thenReturn("src.png");
        when(generations.findAnimatableSource(7L, "src.png")).thenReturn(Optional.empty());
        assertThat(tool.proposeRegenerateWithSeed(12L, "a.png", context)).contains("no longer exists");
        assertThat(holder.getActions()).isEmpty();

        when(generations.findAnimatableSource(7L, "src.png")).thenReturn(Optional.of(mock(Generation.class)));
        assertThat(tool.proposeRegenerateWithSeed(12L, "a.png", context)).contains("Proposal prepared");
        assertThat(holder.getActions()).containsExactly(ChatAction.regenerate(12L, "a.png", "owner/model"));
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
