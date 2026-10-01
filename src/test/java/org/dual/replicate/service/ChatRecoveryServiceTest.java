package org.dual.replicate.service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.dual.replicate.app.generation.port.in.IGenerations;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.dual.replicate.app.generation.domain.Generation;
import org.dual.replicate.app.generation.domain.GenerationStatus;
import org.dual.replicate.repository.ChatMessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatRecoveryServiceTest {

    @Mock
    private IGenerations generationService;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private DeepChatGenerationWatcher watcher;

    @Mock
    private ISystemEvents systemEvents;

    private ChatRecoveryService service() {
        return new ChatRecoveryService(generationService, chatMessageRepository, watcher, systemEvents);
    }

    private static Generation generation(long id, GenerationStatus status, Long conversationId) {
        Generation generation = new Generation("pred-" + id, "owner/model", null, "a cat", null);
        ReflectionTestUtils.setField(generation, "id", id);
        generation.setStatus(status);
        generation.setConversationId(conversationId);
        return generation;
    }

    /** Generazione terminale gia' avanzata dal recupero delle generazioni, senza turno: all'avvio il turno viene scritto subito. */
    @Test
    void startupWritesTheMissingChatTurn() {
        Generation done = generation(1L, GenerationStatus.SUCCEEDED, 7L);
        when(generationService.terminalIdsWithConversation(any(Instant.class), anyInt())).thenReturn(List.of(1L));
        when(chatMessageRepository.findGenerationIdsIn(anyCollection())).thenReturn(List.of());
        when(generationService.findAllById(List.of(1L))).thenReturn(List.of(done));
        when(generationService.inProgress()).thenReturn(List.of());

        service().recoverOnStartup();

        verify(watcher).persistOutcome(done, 7L);
    }

    /** Ancora in corso e legata a una conversazione: il watcher perso col processo viene riavviato. */
    @Test
    void startupRestartsTheLostWatcherForStillRunningChatGenerations() {
        Generation running = generation(2L, GenerationStatus.PROCESSING, 9L);
        Generation noChat = generation(3L, GenerationStatus.PROCESSING, null);
        when(generationService.terminalIdsWithConversation(any(Instant.class), anyInt())).thenReturn(List.of());
        when(generationService.inProgress()).thenReturn(List.of(running, noChat));

        service().recoverOnStartup();

        verify(watcher).watch(2L, 9L, Locale.ITALIAN);
        verify(watcher, never()).watch(3L, null, Locale.ITALIAN);
    }

    /** Un turno gia' scritto non si riscrive: lo sweep sottrae gli id che hanno gia' un turno. */
    @Test
    void sweepSkipsGenerationsThatAlreadyHaveATurnAndWritesTheOthers() {
        Generation orphan = generation(3L, GenerationStatus.FAILED, 4L);
        when(generationService.terminalIdsWithConversation(any(Instant.class), anyInt())).thenReturn(List.of(3L, 5L));
        when(chatMessageRepository.findGenerationIdsIn(anyCollection())).thenReturn(List.of(5L));
        when(generationService.findAllById(List.of(3L))).thenReturn(List.of(orphan));

        service().sweep();

        verify(watcher).persistOutcome(orphan, 4L);
    }
}
