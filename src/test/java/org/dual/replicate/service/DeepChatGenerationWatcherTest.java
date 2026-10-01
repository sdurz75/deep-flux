package org.dual.replicate.service;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.dual.replicate.core.events.domain.CoreEventSource;
import org.dual.replicate.domain.ChatConversation;
import org.dual.replicate.domain.ChatMessage;
import org.dual.replicate.domain.ChatMessageRole;
import org.dual.replicate.domain.Generation;
import org.dual.replicate.domain.GenerationStatus;
import org.dual.replicate.domain.event.ChatMessagePushEvent;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.dual.replicate.replicate.ReplicateException;
import org.dual.replicate.repository.ChatConversationRepository;
import org.dual.replicate.repository.ChatMessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * watch() e' chiamato direttamente sull'istanza (nessun ApplicationContext,
 * quindi nessun proxy @Async): gira sincrono, senza bisogno di attese.
 */
@ExtendWith(MockitoExtension.class)
class DeepChatGenerationWatcherTest {

    @Mock
    private GenerationService generationService;

    @Mock
    private ChatConversationRepository chatConversationRepository;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private GenerationEventBroadcaster broadcaster;

    @Mock
    private Messages i18n;

    @Mock
    private ISystemEvents systemEvents;

    @Test
    void watchPersistsChatMessageAndBroadcastsOnSuccess() {
        DeepChatGenerationWatcher watcher = new DeepChatGenerationWatcher(
                generationService, chatConversationRepository, chatMessageRepository, broadcaster, i18n, systemEvents);

        Generation generation = new Generation("pred-1", "owner/model", null, "a cat", null);
        generation.setStatus(GenerationStatus.SUCCEEDED);
        generation.setImageFilenames(List.of("1-0.png"));
        when(generationService.waitUntilTerminal(eq(1L), any(Duration.class))).thenReturn(generation);

        ChatConversation conversation = new ChatConversation();
        when(chatConversationRepository.findById(7L)).thenReturn(Optional.of(conversation));
        when(chatConversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(i18n.get("deepchat.push.succeeded")).thenReturn("Immagine generata con successo.");

        watcher.watch(1L, 7L, Locale.ITALIAN);

        ArgumentCaptor<ChatMessage> messageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMessageRepository).save(messageCaptor.capture());
        ChatMessage saved = messageCaptor.getValue();
        assertThat(saved.getRole()).isEqualTo(ChatMessageRole.AI);
        assertThat(saved.getContent()).isEqualTo("Immagine generata con successo.");
        assertThat(saved.getGeneration()).isSameAs(generation);

        ArgumentCaptor<ChatMessagePushEvent> pushCaptor =
                ArgumentCaptor.forClass(ChatMessagePushEvent.class);
        verify(broadcaster).broadcastChatMessage(pushCaptor.capture());
        assertThat(pushCaptor.getValue().conversationId()).isEqualTo(7L);
        assertThat(pushCaptor.getValue().text()).isEqualTo("Immagine generata con successo.");
        assertThat(pushCaptor.getValue().files()).extracting("src").containsExactly("/images/1-0.png");
    }

    @Test
    void watchPersistsErrorMessageOnFailure() {
        DeepChatGenerationWatcher watcher = new DeepChatGenerationWatcher(
                generationService, chatConversationRepository, chatMessageRepository, broadcaster, i18n, systemEvents);

        Generation generation = new Generation("pred-1", "owner/model", null, "a cat", null);
        generation.setStatus(GenerationStatus.FAILED);
        generation.setErrorMessage("Replicate ha risposto con errore");
        when(generationService.waitUntilTerminal(eq(1L), any(Duration.class))).thenReturn(generation);

        ChatConversation conversation = new ChatConversation();
        when(chatConversationRepository.findById(7L)).thenReturn(Optional.of(conversation));
        when(chatConversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(i18n.get("deepchat.push.failed", "Replicate ha risposto con errore"))
                .thenReturn("Generazione fallita: Replicate ha risposto con errore");

        watcher.watch(1L, 7L, Locale.ITALIAN);

        ArgumentCaptor<ChatMessage> messageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMessageRepository).save(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getContent()).isEqualTo("Generazione fallita: Replicate ha risposto con errore");

        ArgumentCaptor<ChatMessagePushEvent> pushCaptor =
                ArgumentCaptor.forClass(ChatMessagePushEvent.class);
        verify(broadcaster).broadcastChatMessage(pushCaptor.capture());
        assertThat(pushCaptor.getValue().files()).isNull();
    }

    @Test
    void watchDoesNothingWhenConversationWasDeletedMeanwhile() {
        DeepChatGenerationWatcher watcher = new DeepChatGenerationWatcher(
                generationService, chatConversationRepository, chatMessageRepository, broadcaster, i18n, systemEvents);

        Generation generation = new Generation("pred-1", "owner/model", null, "a cat", null);
        generation.setStatus(GenerationStatus.SUCCEEDED);
        when(generationService.waitUntilTerminal(anyLong(), any(Duration.class))).thenReturn(generation);
        when(chatConversationRepository.findById(7L)).thenReturn(Optional.empty());

        watcher.watch(1L, 7L, Locale.ITALIAN);

        verify(chatMessageRepository, never()).save(any());
        verify(broadcaster, never()).broadcastChatMessage(any());
    }

    /**
     * Regressione: da quando /generations puo' cancellare anche
     * generazioni non terminali (vedi CLAUDE.md), una generazione avviata
     * da /deep-chat puo' sparire mentre questo watcher la sta ancora
     * aspettando (waitUntilTerminal -> refresh -> ReplicateException,
     * vedi GenerationService#saveAndLogIfTerminal/#get). Deve fermarsi
     * silenziosamente, senza scrivere un turno "fallita"/notificare SSE
     * per un id ormai inesistente - stesso trattamento della conversazione
     * cancellata sopra, non un errore da propagare.
     */
    @Test
    void watchStopsSilentlyWhenGenerationWasDeletedMeanwhile() {
        DeepChatGenerationWatcher watcher = new DeepChatGenerationWatcher(
                generationService, chatConversationRepository, chatMessageRepository, broadcaster, i18n, systemEvents);

        when(generationService.waitUntilTerminal(eq(1L), any(Duration.class)))
                .thenThrow(new ReplicateException("generazione non trovata"));

        watcher.watch(1L, 7L, Locale.ITALIAN);

        verify(chatConversationRepository, never()).findById(any());
        verify(chatMessageRepository, never()).save(any());
        verify(broadcaster, never()).broadcastChatMessage(any());
    }

    private DeepChatGenerationWatcher watcher() {
        return new DeepChatGenerationWatcher(
                generationService, chatConversationRepository, chatMessageRepository, broadcaster, i18n, systemEvents);
    }

    /** Errore inatteso con la riga ancora esistente: NON e' una cancellazione, va registrato (prima veniva ingoiato in silenzio). */
    @Test
    void watchRecordsUnexpectedFailureInsteadOfSwallowingIt() {
        RuntimeException boom = new RuntimeException("db giu'");
        when(generationService.waitUntilTerminal(eq(1L), any(Duration.class))).thenThrow(boom);
        when(generationService.exists(1L)).thenReturn(true);

        watcher().watch(1L, 7L, Locale.ITALIAN);

        verify(systemEvents).record(org.dual.replicate.core.events.domain.CoreEventSource.INTERNAL, "watchGeneration", boom, "generation:1");
        verify(chatMessageRepository, never()).save(any());
        verify(broadcaster, never()).broadcastChatMessage(any());
    }

    /** Thread interrotto / tetto di sicurezza: generazione ancora in corso, nessun falso turno "fallita". */
    @Test
    void watchWritesNoFailedTurnWhenGenerationIsStillRunning() {
        Generation generation = new Generation("pred-1", "owner/model", null, "a cat", null);
        generation.setStatus(GenerationStatus.PROCESSING);
        when(generationService.waitUntilTerminal(eq(1L), any(Duration.class))).thenReturn(generation);

        watcher().watch(1L, 7L, Locale.ITALIAN);

        verify(chatMessageRepository, never()).save(any());
        verify(broadcaster, never()).broadcastChatMessage(any());
    }

    /** Watcher e recupero possono incrociarsi: un secondo turno di esito per la stessa generazione non va scritto. */
    @Test
    void persistOutcomeIsIdempotent() {
        Generation generation = new Generation("pred-1", "owner/model", null, "a cat", null);
        org.springframework.test.util.ReflectionTestUtils.setField(generation, "id", 1L);
        generation.setStatus(GenerationStatus.SUCCEEDED);
        when(chatMessageRepository.existsByGenerationId(1L)).thenReturn(true);

        assertThat(watcher().persistOutcome(generation, 7L)).isFalse();

        verify(chatMessageRepository, never()).save(any());
        verify(broadcaster, never()).broadcastChatMessage(any());
    }

    /** Se scrivere il turno fallisce, l'errore e' registrato e non risale (lo sweep di recupero riprovera'). */
    @Test
    void persistOutcomeRecordsPersistenceFailure() {
        Generation generation = new Generation("pred-1", "owner/model", null, "a cat", null);
        org.springframework.test.util.ReflectionTestUtils.setField(generation, "id", 1L);
        generation.setStatus(GenerationStatus.SUCCEEDED);
        RuntimeException boom = new RuntimeException("db giu'");
        when(chatConversationRepository.findById(7L)).thenThrow(boom);

        assertThat(watcher().persistOutcome(generation, 7L)).isFalse();

        verify(systemEvents).record(org.dual.replicate.core.events.domain.CoreEventSource.INTERNAL, "persistChatTurn", boom, "generation:1");
    }
}
