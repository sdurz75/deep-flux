package org.dual.replicate.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.dual.replicate.domain.ChatConversation;
import org.dual.replicate.i18n.Messages;
import org.dual.replicate.repository.ChatConversationRepository;
import org.dual.replicate.repository.ChatMessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatClient.Builder e' mockato con RETURNS_DEEP_STUBS: e' un'interfaccia
 * fluente (defaultSystem/defaultTools/build/prompt/messages/toolContext/call).
 * Lo stub va impostato ripercorrendo la catena con ArgumentMatchers (non con
 * valori letterali): un deep stub NON restituisce lo stesso sotto-mock per
 * argomenti diversi (verificato dal vivo - con valori letterali diversi da
 * quelli reali passati da DeepChatService la catena risolveva un sotto-mock
 * diverso, non stubbato, e chatResponse() tornava null), quindi il numero
 * di matcher per ogni chiamata deve combaciare esattamente con gli
 * argomenti/vararg di quel metodo (1 per defaultSystem(String), 2 per
 * defaultTools(Object...) - chiamato con 2 argomenti in DeepChatService,
 * ecc.).
 */
@ExtendWith(MockitoExtension.class)
class DeepChatServiceTest {

    @Mock
    private WebSearchTool webSearchTool;

    @Mock
    private ImageGenerationTool imageGenerationTool;

    @Mock
    private ChatConversationRepository chatConversationRepository;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private DeepChatGenerationWatcher generationWatcher;

    @Mock
    private Messages i18n;

    @Mock
    private AppErrorService appErrors;

    @Test
    void replyThrowsWhenConversationNotFound() {
        ChatClient.Builder chatClientBuilder = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
        DeepChatService service = new DeepChatService(chatClientBuilder, webSearchTool, imageGenerationTool,
                chatConversationRepository, chatMessageRepository, generationWatcher, i18n, appErrors, "guida");
        when(chatConversationRepository.findById(1L)).thenReturn(Optional.empty());
        when(i18n.get("deepchat.error.conversationNotFound")).thenReturn("Conversazione non trovata");

        assertThatThrownBy(() -> service.reply(1L, List.of(), null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Conversazione non trovata");
    }

    @Test
    void replySetsTitleFromFirstUserTurnAndBumpsUpdatedAtBeforeCallingTheModel() {
        ChatClient.Builder chatClientBuilder = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
        ChatResponse chatResponse = new ChatResponse(
                List.of(new org.springframework.ai.chat.model.Generation(new AssistantMessage("Ciao! Come posso aiutarti?"))),
                ChatResponseMetadata.builder().build());
        when(chatClientBuilder.defaultSystem(anyString()).defaultTools(any(), any()).build()
                .prompt().messages(anyList()).toolContext(anyMap()).call().chatResponse())
                .thenReturn(chatResponse);

        DeepChatService service = new DeepChatService(chatClientBuilder, webSearchTool, imageGenerationTool,
                chatConversationRepository, chatMessageRepository, generationWatcher, i18n, appErrors, "guida");

        ChatConversation conversation = new ChatConversation();
        Instant createdAt = conversation.getUpdatedAt();
        when(chatConversationRepository.findById(7L)).thenReturn(Optional.of(conversation));
        when(chatConversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DeepChatService.Turn userTurn = new DeepChatService.Turn("user", "Genera un gatto arancione");
        DeepChatService.Reply reply = service.reply(7L, List.of(userTurn), "owner/model", Map.of());

        assertThat(reply.text()).isEqualTo("Ciao! Come posso aiutarti?");
        assertThat(conversation.getTitle()).isEqualTo("Genera un gatto arancione");
        assertThat(conversation.getUpdatedAt()).isAfterOrEqualTo(createdAt);
        verify(chatConversationRepository).save(conversation);
        verify(chatMessageRepository, org.mockito.Mockito.times(2)).save(any());
    }

    /**
     * Chiamata LLM fallita: l'errore e' registrato, in cronologia c'e' un turno d'errore (il turno USER non resta
     * orfano), l'eccezione porta il messaggio per l'utente ed e' segnalata come GIA' registrata.
     */
    @Test
    void replyRecordsFailureWritesAnErrorTurnAndSignalsAlreadyRecorded() {
        ChatClient.Builder chatClientBuilder = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
        RuntimeException outage = new RuntimeException("OpenRouter giu'");
        when(chatClientBuilder.defaultSystem(anyString()).defaultTools(any(), any()).build()
                .prompt().messages(anyList()).toolContext(anyMap()).call().chatResponse())
                .thenThrow(outage);
        DeepChatService service = new DeepChatService(chatClientBuilder, webSearchTool, imageGenerationTool,
                chatConversationRepository, chatMessageRepository, generationWatcher, i18n, appErrors, "guida");
        ChatConversation conversation = new ChatConversation();
        when(chatConversationRepository.findById(7L)).thenReturn(Optional.of(conversation));
        when(chatConversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(i18n.get(org.mockito.ArgumentMatchers.eq("deepchat.error.contactAssistant"), any())).thenReturn("Errore assistente");

        assertThatThrownBy(() -> service.reply(7L, List.of(new DeepChatService.Turn("user", "ciao")), "owner/model", Map.of()))
                .isInstanceOf(DeepChatFailedException.class)
                .hasMessage("Errore assistente");

        verify(appErrors).record(eq(org.dual.replicate.domain.AppErrorSource.OPENROUTER), eq("chatTurn"),
                org.mockito.ArgumentMatchers.argThat(e -> e instanceof OpenRouterException && e.getCause() == outage), eq(null), eq(conversation.getId()));
        org.mockito.ArgumentCaptor<org.dual.replicate.domain.ChatMessage> saved =
                org.mockito.ArgumentCaptor.forClass(org.dual.replicate.domain.ChatMessage.class);
        verify(chatMessageRepository, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getRole()).isEqualTo(org.dual.replicate.domain.ChatMessageRole.USER);
        assertThat(saved.getAllValues().get(1).isError()).isTrue();
        assertThat(saved.getAllValues().get(1).getContent()).isEqualTo("Errore assistente");
    }
}
