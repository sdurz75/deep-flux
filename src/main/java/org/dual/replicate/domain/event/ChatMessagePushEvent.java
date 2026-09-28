package org.dual.replicate.domain.event;

import org.dual.replicate.service.DeepChatService;

import java.util.List;

/**
 * Payload dell'evento "chat-message": vocabolario JSON di deep-chat, vedi DeepChatService.FileRef.
 */
public record ChatMessagePushEvent(Long conversationId, String text, List<DeepChatService.FileRef> files) {
}
