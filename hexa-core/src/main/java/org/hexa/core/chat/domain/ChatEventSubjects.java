package org.hexa.core.chat.domain;

/** Il {@code subject} degli eventi di sistema della chat ({@code conversation:5}), stesso formato di {@code token:12}: entra nella chiave di serie. */
public final class ChatEventSubjects {

    public static final String CONVERSATION = "conversation";

    private ChatEventSubjects() {
    }

    /** Il subject degli eventi di una conversazione, o {@code null} se l'id non c'e'. */
    public static String ofConversation(Long conversationId) {
        return conversationId == null ? null : CONVERSATION + ":" + conversationId;
    }
}
