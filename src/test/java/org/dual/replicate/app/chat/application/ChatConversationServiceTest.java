package org.dual.replicate.app.chat.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.dual.replicate.app.chat.domain.ChatConversation;
import org.dual.replicate.app.chat.domain.ChatMessage;
import org.dual.replicate.app.chat.domain.ChatMessageRole;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.dual.replicate.app.chat.port.out.IChatConversationStore;
import org.dual.replicate.app.chat.port.out.IChatMessageStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatConversationServiceTest {

    @Mock
    private IChatConversationStore conversationRepository;

    @Mock
    private IChatMessageStore chatMessageRepository;

    @Mock
    private Messages messages;

    @Test
    void resolveDefaultReturnsMostRecentWhenPresent() {
        ChatConversationService service = new ChatConversationService(conversationRepository, chatMessageRepository, messages);
        ChatConversation existing = new ChatConversation();
        when(conversationRepository.findMostRecent()).thenReturn(Optional.of(existing));

        ChatConversation result = service.resolveDefault();

        assertThat(result).isSameAs(existing);
        verify(conversationRepository, never()).save(any());
    }

    @Test
    void resolveDefaultCreatesNewWhenNoneExist() {
        ChatConversationService service = new ChatConversationService(conversationRepository, chatMessageRepository, messages);
        when(conversationRepository.findMostRecent()).thenReturn(Optional.empty());
        when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ChatConversation result = service.resolveDefault();

        assertThat(result).isNotNull();
        verify(conversationRepository).save(any(ChatConversation.class));
    }

    /** Rinominare non e' attivita' di chat: non deve riordinare la sidebar per recenza (vedi ChatConversationService). */
    @Test
    void renameSetsTitleWithoutTouchingUpdatedAt() {
        ChatConversationService service = new ChatConversationService(conversationRepository, chatMessageRepository, messages);
        ChatConversation conversation = new ChatConversation();
        Instant originalUpdatedAt = conversation.getUpdatedAt();
        when(conversationRepository.findById(1L)).thenReturn(Optional.of(conversation));
        when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ChatConversation result = service.rename(1L, "Nuovo titolo");

        assertThat(result.getTitle()).isEqualTo("Nuovo titolo");
        assertThat(result.getUpdatedAt()).isEqualTo(originalUpdatedAt);
    }

    /**
     * Una rinomina lasciata in bianco in UI (input vuoto/di soli spazi)
     * deve tornare a null, non essere salvata come stringa vuota:
     * altrimenti l'Elvis nel template (${c.title} ?: #{...untitled})
     * smetterebbe di scattare (una stringa vuota non e' null) e
     * ChatService non deriverebbe piu' un titolo dal primo turno
     * (il suo controllo e' proprio title == null).
     */
    @Test
    void renameWithBlankTitleNormalizesToNull() {
        ChatConversationService service = new ChatConversationService(conversationRepository, chatMessageRepository, messages);
        ChatConversation conversation = new ChatConversation();
        when(conversationRepository.findById(1L)).thenReturn(Optional.of(conversation));
        when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ChatConversation result = service.rename(1L, "   ");

        assertThat(result.getTitle()).isNull();
    }

    /** Le Generation referenziate dai turni cancellati appartengono al registro globale della galleria: non devono essere toccate (vedi CLAUDE.md, Scopo). */
    @Test
    void deleteRemovesMessagesBeforeConversationAndLeavesGenerationsUntouched() {
        ChatConversationService service = new ChatConversationService(conversationRepository, chatMessageRepository, messages);
        ChatConversation conversation = new ChatConversation();
        ChatMessage message = new ChatMessage(conversation, ChatMessageRole.USER, "ciao", null);
        when(conversationRepository.findById(1L)).thenReturn(Optional.of(conversation));
        when(chatMessageRepository.findByConversation(1L)).thenReturn(List.of(message));

        service.delete(1L);

        verify(chatMessageRepository).deleteAll(List.of(message));
        verify(conversationRepository).delete(conversation);
    }
}
