package org.dual.replicate.core.chat.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.dual.replicate.core.chat.domain.ChatConversation;
import org.dual.replicate.core.chat.domain.ChatMessage;
import org.dual.replicate.core.chat.domain.ChatMessageRole;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.dual.replicate.core.chat.port.in.IChatConversations;
import org.dual.replicate.core.chat.port.out.IChatConversationStore;
import org.dual.replicate.core.chat.port.out.IChatMessageStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
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
        ChatConversationService service = new ChatConversationService(conversationRepository, chatMessageRepository, messages, new tools.jackson.databind.ObjectMapper(), org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class));
        ChatConversation existing = new ChatConversation();
        when(conversationRepository.findMostRecent()).thenReturn(Optional.of(existing));

        ChatConversation result = service.resolveDefault();

        assertThat(result).isSameAs(existing);
        verify(conversationRepository, never()).save(any());
    }

    @Test
    void resolveDefaultCreatesNewWhenNoneExist() {
        ChatConversationService service = new ChatConversationService(conversationRepository, chatMessageRepository, messages, new tools.jackson.databind.ObjectMapper(), org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class));
        when(conversationRepository.findMostRecent()).thenReturn(Optional.empty());
        when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ChatConversation result = service.resolveDefault();

        assertThat(result).isNotNull();
        verify(conversationRepository).save(any(ChatConversation.class));
    }

    /** Rinominare non e' attivita' di chat: non deve riordinare la sidebar per recenza (vedi ChatConversationService). */
    @Test
    void renameSetsTitleWithoutTouchingUpdatedAt() {
        ChatConversationService service = new ChatConversationService(conversationRepository, chatMessageRepository, messages, new tools.jackson.databind.ObjectMapper(), org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class));
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
        ChatConversationService service = new ChatConversationService(conversationRepository, chatMessageRepository, messages, new tools.jackson.databind.ObjectMapper(), org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class));
        ChatConversation conversation = new ChatConversation();
        when(conversationRepository.findById(1L)).thenReturn(Optional.of(conversation));
        when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ChatConversation result = service.rename(1L, "   ");

        assertThat(result.getTitle()).isNull();
    }

    /** Le Generation appartengono al registro globale della galleria: non si cancellano, si scollegano soltanto dalla conversazione (vedi CLAUDE.md, Scopo). */
    @Test
    void deleteRemovesMessagesBeforeConversationAndAnnouncesItSoGenerationsAreOnlyDetached() {
        org.springframework.context.ApplicationEventPublisher events = org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class);
        ChatConversationService service = new ChatConversationService(conversationRepository, chatMessageRepository, messages, new tools.jackson.databind.ObjectMapper(), events);
        ChatConversation conversation = new ChatConversation();
        ChatMessage message = new ChatMessage(conversation, ChatMessageRole.USER, "ciao", null);
        when(conversationRepository.findById(1L)).thenReturn(Optional.of(conversation));
        when(chatMessageRepository.findByConversation(1L)).thenReturn(List.of(message));

        service.delete(1L);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(chatMessageRepository, events, conversationRepository);
        order.verify(chatMessageRepository).deleteAll(List.of(message));
        order.verify(events).publishEvent(new org.dual.replicate.core.chat.domain.event.ChatConversationDeletedEvent(1L));
        order.verify(conversationRepository).delete(conversation);
    }

    @Test
    void addTagNormalizesDedupesCapsAndDoesNotTouchUpdatedAt() {
        org.springframework.context.ApplicationEventPublisher events = org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class);
        ChatConversationService service = new ChatConversationService(conversationRepository, chatMessageRepository, messages,
                new tools.jackson.databind.ObjectMapper(), events);
        ChatConversation conversation = new ChatConversation();
        java.time.Instant before = conversation.getUpdatedAt();
        when(conversationRepository.findById(1L)).thenReturn(Optional.of(conversation));
        when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(messages.get(org.mockito.ArgumentMatchers.eq("tags.error.tooMany"), org.mockito.ArgumentMatchers.<Object[]>any())).thenReturn("troppi");

        service.addTag(1L, "  Mare ");
        service.addTag(1L, "mare");   // doppione: nessun effetto
        service.addTag(1L, "   ");    // vuoto: ignorato

        assertThat(conversation.getTags()).containsExactly("mare");
        assertThat(conversation.getUpdatedAt()).isEqualTo(before);
        verify(events, org.mockito.Mockito.times(1)).publishEvent(any(org.dual.replicate.core.chat.domain.event.ChatConversationChangedEvent.class));

        for (int i = 0; i < org.dual.replicate.core.kernel.Tags.MAX_PER_ENTITY - 1; i++) {
            service.addTag(1L, "t" + i);
        }
        assertThat(conversation.getTags()).hasSize(org.dual.replicate.core.kernel.Tags.MAX_PER_ENTITY);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.addTag(1L, "uno-di-troppo")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void removeTagOnlyPublishesWhenSomethingChanged() {
        org.springframework.context.ApplicationEventPublisher events = org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class);
        ChatConversationService service = new ChatConversationService(conversationRepository, chatMessageRepository, messages,
                new tools.jackson.databind.ObjectMapper(), events);
        ChatConversation conversation = new ChatConversation();
        conversation.getTags().add("mare");
        when(conversationRepository.findById(1L)).thenReturn(Optional.of(conversation));
        when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.removeTag(1L, "Altro");
        verify(events, never()).publishEvent(any(Object.class));
        service.removeTag(1L, "MARE");

        assertThat(conversation.getTags()).isEmpty();
        verify(events).publishEvent(any(org.dual.replicate.core.chat.domain.event.ChatConversationChangedEvent.class));
    }

    private ChatConversationService settingsService() {
        lenient().when(messages.get("deepchat.error.settingsInvalid")).thenReturn("non valida");
        return new ChatConversationService(conversationRepository, chatMessageRepository, messages, new tools.jackson.databind.ObjectMapper(), org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class));
    }

    /** Ogni conversazione nuova parte dai default del catalogo ("{}"); NULL esiste solo per le righe precedenti alla migrazione. */
    @Test
    void aNewConversationStartsWithEmptySettings() {
        assertThat(new ChatConversation().getGenerationSettingsJson()).isEqualTo("{}");
    }

    @Test
    void saveGenerationSettingsStoresTheRawJsonWithoutTouchingTheRecency() {
        ChatConversation conversation = new ChatConversation();
        java.time.Instant before = conversation.getUpdatedAt();
        when(conversationRepository.findById(5L)).thenReturn(Optional.of(conversation));
        String json = "{\"model\":\"owner/m\",\"lora_weights\":\"me/face\"}";

        settingsService().saveGenerationSettings(5L, json);

        assertThat(conversation.getGenerationSettingsJson()).isEqualTo(json);
        assertThat(conversation.getUpdatedAt()).isEqualTo(before);
        verify(conversationRepository).save(conversation);
    }

    @Test
    void saveGenerationSettingsRejectsNonObjectsMalformedAndOversizedJson() {
        ChatConversation conversation = new ChatConversation();
        when(conversationRepository.findById(5L)).thenReturn(Optional.of(conversation));
        String oversized = "{\"x\":\"" + "a".repeat(IChatConversations.MAX_SETTINGS_BYTES) + "\"}";

        for (String invalid : new String[]{"[1,2]", "\"text\"", "42", "{non json", "", null, oversized}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> settingsService().saveGenerationSettings(5L, invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(conversation.getGenerationSettingsJson()).isEqualTo("{}");
        verify(conversationRepository, never()).save(any());
    }
}
