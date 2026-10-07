package org.hexa.app.chat.application;

import java.util.List;

import org.hexa.app.generation.domain.Generation;
import org.hexa.app.generation.domain.GenerationStatus;
import org.hexa.app.generation.port.in.IGenerations;
import org.hexa.core.chat.domain.ChatOutcomeView;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Lato app dell'esito: come una generazione si presenta al modello (nota in inglese) e all'utente (allegati). */
class GenerationChatOutcomesTest {

    private Generation generation(long id, GenerationStatus status) {
        Generation generation = new Generation("pred-" + id, "owner/model", null, "a cat", "{}");
        ReflectionTestUtils.setField(generation, "id", id);
        generation.setStatus(status);
        return generation;
    }

    @Test
    void aFinishedGenerationListsItsFilesAndAFailedOneItsReason() {
        Generation done = generation(12, GenerationStatus.SUCCEEDED);
        done.setImageFilenames(List.of("a.png", "b.png"));
        Generation failed = generation(13, GenerationStatus.FAILED);
        failed.setErrorMessage("Generazione annullata.");
        IGenerations generations = mock(IGenerations.class);
        when(generations.findAllById(List.of(12L, 13L))).thenReturn(List.of(done, failed));

        var views = new GenerationChatOutcomes(generations).resolve(List.of(12L, 13L));

        assertThat(views.get(12L).modelNote()).isEqualTo("Generation #12 finished: files a.png, b.png.");
        assertThat(views.get(12L).files()).extracting("src").containsExactly("/images/a.png", "/images/b.png");
        assertThat(views.get(13L)).extracting(ChatOutcomeView::modelNote).isEqualTo("Generation #13 failed: Generazione annullata.");
        assertThat(views.get(13L).files()).isNull();
    }

    @Test
    void anOutcomeStillInProgressSaysSo() {
        assertThat(GenerationChatOutcomes.outcomeNote(generation(5, GenerationStatus.PROCESSING))).isEqualTo("Generation #5 is still in progress.");
        assertThat(GenerationChatOutcomes.outcomeNote(generation(6, GenerationStatus.FAILED))).isEqualTo("Generation #6 failed: unknown error");
    }
}
