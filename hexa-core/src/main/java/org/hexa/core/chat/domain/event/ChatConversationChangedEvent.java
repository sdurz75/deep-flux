package org.hexa.core.chat.domain.event;

/** Titolo o tag di una conversazione sono cambiati (ChatConversationService#rename/#addTag/#removeTag): l'indice di ricerca va riallineato. */
public record ChatConversationChangedEvent(Long conversationId) {
}
