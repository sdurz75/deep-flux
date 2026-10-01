package org.dual.replicate.app.chat.domain.event;

import java.util.List;

import org.dual.replicate.app.chat.domain.FileRef;

/**
 * Payload dell'evento "chat-message": vocabolario JSON di deep-chat, vedi {@link FileRef}.
 */
public record ChatMessagePushEvent(Long conversationId, Long generationId, String text, List<FileRef> files) {
}
