package org.dual.replicate.core.chat.domain.event;

/**
 * Pubblicato da {@code ChatConversationService#delete} (nella stessa transazione, prima di rimuovere la riga) cosi' chi ha dati che
 * puntano alla conversazione senza una FK li scollega: per l'app le generazioni ({@code generation.conversation_id} non ha FK).
 */
public record ChatConversationDeletedEvent(Long conversationId) {
}
