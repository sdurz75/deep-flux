package org.dual.replicate.service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.dual.replicate.core.events.domain.CoreEventSource;
import org.dual.replicate.domain.Generation;
import org.dual.replicate.domain.GenerationStatus;
import org.dual.replicate.repository.GenerationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GenerationRecoveryServiceTest {

    @Mock
    private GenerationRepository repository;

    @Mock
    private GenerationService generationService;

    @Mock
    private DeepChatGenerationWatcher watcher;

    @Mock
    private ISystemEvents systemEvents;

    private GenerationRecoveryService service() {
        return new GenerationRecoveryService(repository, generationService, watcher, systemEvents);
    }

    private static Generation generation(long id, GenerationStatus status, Long conversationId) {
        Generation generation = new Generation("pred-" + id, "owner/model", null, "a cat", null);
        ReflectionTestUtils.setField(generation, "id", id);
        generation.setStatus(status);
        generation.setConversationId(conversationId);
        return generation;
    }

    /** Riavvio a meta' generazione: la riga in corso viene fatta avanzare, e se e' terminale il turno di chat mancante viene scritto. */
    @Test
    void startupRefreshesInProgressGenerationsAndWritesTheMissingChatTurn() {
        Generation stuck = generation(1L, GenerationStatus.PROCESSING, 7L);
        Generation done = generation(1L, GenerationStatus.SUCCEEDED, 7L);
        when(repository.findByStatusIn(anyCollection())).thenReturn(List.of(stuck));
        when(generationService.refresh(1L)).thenReturn(done);
        when(repository.findTerminalWithoutChatTurn(anyCollection(), any(Instant.class))).thenReturn(List.of());

        service().recoverOnStartup();

        verify(watcher).persistOutcome(done, 7L);
    }

    /** Ancora in corso dopo il refresh e legata a una conversazione: il watcher perso col processo viene riavviato. */
    @Test
    void startupRestartsTheLostWatcherForStillRunningChatGenerations() {
        Generation running = generation(2L, GenerationStatus.PROCESSING, 9L);
        when(repository.findByStatusIn(anyCollection())).thenReturn(List.of(running));
        when(generationService.refresh(2L)).thenReturn(running);
        when(repository.findTerminalWithoutChatTurn(anyCollection(), any(Instant.class))).thenReturn(List.of());

        service().recoverOnStartup();

        verify(watcher).watch(2L, 9L, Locale.ITALIAN);
    }

    /** Un errore su UNA riga viene registrato e non ferma il recupero delle altre. */
    @Test
    void oneFailingGenerationIsRecordedAndDoesNotStopTheOthers() {
        Generation bad = generation(1L, GenerationStatus.PROCESSING, null);
        Generation good = generation(2L, GenerationStatus.PROCESSING, null);
        RuntimeException boom = new RuntimeException("db giu'");
        when(repository.findByStatusIn(anyCollection())).thenReturn(List.of(bad, good));
        when(generationService.refresh(1L)).thenThrow(boom);
        when(generationService.exists(1L)).thenReturn(true);
        when(generationService.refresh(2L)).thenReturn(good);
        when(repository.findTerminalWithoutChatTurn(anyCollection(), any(Instant.class))).thenReturn(List.of());

        service().recoverOnStartup();

        verify(systemEvents).record(CoreEventSource.INTERNAL, "recoverGeneration", boom, "generation:1");
        verify(generationService).refresh(2L);
    }

    /** Lo sweep periodico tocca solo le righe OLTRE il timeout (nessuna corsa con watcher/poller ancora attivi). */
    @Test
    void sweepOnlyTouchesOverdueGenerations() {
        Generation fresh = generation(1L, GenerationStatus.PROCESSING, null);
        Generation overdue = generation(2L, GenerationStatus.PROCESSING, null);
        when(repository.findByStatusIn(anyCollection())).thenReturn(List.of(fresh, overdue));
        when(generationService.isOverdue(fresh)).thenReturn(false);
        when(generationService.isOverdue(overdue)).thenReturn(true);
        when(generationService.refresh(2L)).thenReturn(overdue);
        when(repository.findTerminalWithoutChatTurn(anyCollection(), any(Instant.class))).thenReturn(List.of());

        service().sweep();

        verify(generationService, never()).refresh(1L);
        verify(generationService).refresh(2L);
    }

    /** Generazioni terminali di una conversazione senza turno (watcher morto): lo sweep scrive il turno mancante. */
    @Test
    void sweepWritesMissingChatTurnsOfTerminalGenerations() {
        Generation orphan = generation(3L, GenerationStatus.FAILED, 4L);
        when(repository.findByStatusIn(anyCollection())).thenReturn(List.of());
        when(repository.findTerminalWithoutChatTurn(anyCollection(), any(Instant.class))).thenReturn(List.of(orphan));

        service().sweep();

        verify(watcher).persistOutcome(orphan, 4L);
    }
}
