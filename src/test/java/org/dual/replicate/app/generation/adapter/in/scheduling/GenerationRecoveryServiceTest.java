package org.dual.replicate.app.generation.adapter.in.scheduling;

import java.util.List;

import org.dual.replicate.app.generation.port.in.IGenerations;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.dual.replicate.core.events.domain.CoreEventSource;
import org.dual.replicate.app.generation.domain.Generation;
import org.dual.replicate.app.generation.domain.GenerationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GenerationRecoveryServiceTest {

    @Mock
    private IGenerations generationService;

    @Mock
    private ISystemEvents systemEvents;

    private GenerationRecoveryService service() {
        return new GenerationRecoveryService(generationService, systemEvents);
    }

    private static Generation generation(long id, GenerationStatus status, Long conversationId) {
        Generation generation = new Generation("pred-" + id, "owner/model", null, "a cat", null);
        ReflectionTestUtils.setField(generation, "id", id);
        generation.setStatus(status);
        generation.setConversationId(conversationId);
        return generation;
    }

    /** Riavvio a meta' generazione: la riga in corso viene fatta avanzare. */
    @Test
    void startupRefreshesInProgressGenerations() {
        Generation stuck = generation(1L, GenerationStatus.PROCESSING, 7L);
        when(generationService.inProgress()).thenReturn(List.of(stuck));

        service().recoverOnStartup();

        verify(generationService).refresh(1L);
    }

    /** Un errore su UNA riga viene registrato e non ferma il recupero delle altre. */
    @Test
    void oneFailingGenerationIsRecordedAndDoesNotStopTheOthers() {
        Generation bad = generation(1L, GenerationStatus.PROCESSING, null);
        Generation good = generation(2L, GenerationStatus.PROCESSING, null);
        RuntimeException boom = new RuntimeException("db giu'");
        when(generationService.inProgress()).thenReturn(List.of(bad, good));
        when(generationService.refresh(1L)).thenThrow(boom);
        when(generationService.exists(1L)).thenReturn(true);

        service().recoverOnStartup();

        verify(systemEvents).record(CoreEventSource.INTERNAL, "recoverGeneration", boom, "generation:1");
        verify(generationService).refresh(2L);
    }

    /** Lo sweep periodico tocca solo le righe OLTRE il timeout (nessuna corsa con watcher/poller ancora attivi). */
    @Test
    void sweepOnlyTouchesOverdueGenerations() {
        Generation fresh = generation(1L, GenerationStatus.PROCESSING, null);
        Generation overdue = generation(2L, GenerationStatus.PROCESSING, null);
        when(generationService.inProgress()).thenReturn(List.of(fresh, overdue));
        when(generationService.isOverdue(fresh)).thenReturn(false);
        when(generationService.isOverdue(overdue)).thenReturn(true);

        service().sweep();

        verify(generationService, never()).refresh(1L);
        verify(generationService).refresh(2L);
    }
}
