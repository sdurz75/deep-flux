package org.dual.replicate.app.chat.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.dual.replicate.app.chat.domain.AssistantException;
import org.dual.replicate.app.chat.domain.ChatAction;
import org.dual.replicate.app.chat.domain.ChatConversation;
import org.dual.replicate.app.chat.domain.ChatMessage;
import org.dual.replicate.app.chat.domain.ChatMessageRole;
import org.dual.replicate.app.chat.domain.ChatReply;
import org.dual.replicate.app.chat.domain.ChatTurn;
import org.dual.replicate.app.chat.domain.DeepChatFailedException;
import org.dual.replicate.app.chat.port.out.IAssistant;
import org.dual.replicate.app.chat.port.out.IChatConversationStore;
import org.dual.replicate.app.chat.port.out.IChatMessageStore;
import org.dual.replicate.app.generation.port.in.IGenerations;
import org.dual.replicate.app.shared.domain.AppEventSource;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** L'assistente (LLM + tool) e' una porta: qui si prova solo l'orchestrazione del turno, senza Spring AI (vedi SpringAiAssistantTest). */
@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private IAssistant assistant;

    @Mock
    private IChatConversationStore conversations;

    @Mock
    private IChatMessageStore messages;

    @Mock
    private IGenerations generations;

    @Mock
    private ChatGenerationWatcher generationWatcher;

    @Mock
    private Messages i18n;

    @Mock
    private ISystemEvents systemEvents;

    private ChatService service() {
        return new ChatService(assistant, conversations, messages, generations, generationWatcher, i18n, systemEvents);
    }

    @Test
    void replyThrowsWhenConversationNotFound() {
        when(conversations.findById(1L)).thenReturn(Optional.empty());
        when(i18n.get("deepchat.error.conversationNotFound")).thenReturn("Conversazione non trovata");

        assertThatThrownBy(() -> service().reply(1L, List.of(), null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Conversazione non trovata");
    }

    @Test
    void replySetsTitleFromFirstUserTurnAndBumpsUpdatedAtBeforeCallingTheModel() {
        ChatConversation conversation = new ChatConversation();
        Instant createdAt = conversation.getUpdatedAt();
        when(conversations.findById(7L)).thenReturn(Optional.of(conversation));
        when(conversations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        List<ChatTurn> history = List.of(new ChatTurn("user", "Genera un gatto arancione"));
        when(assistant.respond(any(), eq(history), eq("owner/model"), eq(Map.of()))).thenReturn(new ChatReply("Ciao! Come posso aiutarti?", List.of()));

        ChatReply reply = service().reply(7L, history, "owner/model", Map.of());

        assertThat(reply.text()).isEqualTo("Ciao! Come posso aiutarti?");
        assertThat(conversation.getTitle()).isEqualTo("Genera un gatto arancione");
        assertThat(conversation.getUpdatedAt()).isAfterOrEqualTo(createdAt);
        verify(conversations).save(conversation);
        verify(messages, times(2)).save(any());
    }

    /** Le generazioni avviate dai tool nel turno restano legate alla conversazione e ricevono il loro watcher. */
    @Test
    void replyAttachesAndWatchesTheGenerationsStartedInTheTurn() {
        ChatConversation conversation = new ChatConversation();
        when(conversations.findById(7L)).thenReturn(Optional.of(conversation));
        when(conversations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(assistant.respond(any(), any(), any(), anyMap())).thenReturn(new ChatReply("Sto generando", List.of(42L)));

        ChatReply reply = service().reply(7L, List.of(new ChatTurn("user", "genera")), "owner/model", Map.of());

        assertThat(reply.startedGenerationIds()).containsExactly(42L);
        verify(generations).attachToConversation(eq(42L), any());
        verify(generationWatcher).watch(eq(42L), any(), any());
    }

    /** Le azioni proposte dall'assistente arrivano alla risposta cosi' come sono: il turno non ne esegue nessuna. */
    @Test
    void replyPassesTheProposedActionsThrough() {
        ChatConversation conversation = new ChatConversation();
        when(conversations.findById(7L)).thenReturn(Optional.of(conversation));
        when(conversations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(assistant.respond(any(), any(), any(), anyMap()))
                .thenReturn(new ChatReply("Preparato", List.of(), List.of(ChatAction.delete(12L))));

        ChatReply reply = service().reply(7L, List.of(new ChatTurn("user", "cancella la 12")), "owner/model", Map.of());

        assertThat(reply.actions()).containsExactly(ChatAction.delete(12L));
        org.mockito.Mockito.verifyNoInteractions(generations);
    }

    /**
     * Chiamata LLM fallita: l'errore e' registrato, in cronologia c'e' un turno d'errore (il turno USER non resta
     * orfano), l'eccezione porta il messaggio per l'utente ed e' segnalata come GIA' registrata. Una generazione gia'
     * avviata dal tool prima del guasto ha comunque il suo watcher.
     */
    @Test
    void replyRecordsFailureWritesAnErrorTurnAndSignalsAlreadyRecorded() {
        RuntimeException outage = new RuntimeException("OpenRouter giu'");
        ChatConversation conversation = new ChatConversation();
        when(conversations.findById(7L)).thenReturn(Optional.of(conversation));
        when(conversations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(assistant.respond(any(), any(), any(), anyMap())).thenThrow(new AssistantException(outage, List.of(9L)));
        when(i18n.get(eq("deepchat.error.contactAssistant"), any())).thenReturn("Errore assistente");

        assertThatThrownBy(() -> service().reply(7L, List.of(new ChatTurn("user", "ciao")), "owner/model", Map.of()))
                .isInstanceOf(DeepChatFailedException.class)
                .hasMessage("Errore assistente");

        verify(systemEvents).record(eq(AppEventSource.OPENROUTER), eq("chatTurn"), eq(outage),
                org.mockito.ArgumentMatchers.isNull(String.class));
        ArgumentCaptor<ChatMessage> saved = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messages, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getRole()).isEqualTo(ChatMessageRole.USER);
        assertThat(saved.getAllValues().get(1).isError()).isTrue();
        assertThat(saved.getAllValues().get(1).getContent()).isEqualTo("Errore assistente");
        verify(generationWatcher).watch(eq(9L), any(), any());
    }
}
