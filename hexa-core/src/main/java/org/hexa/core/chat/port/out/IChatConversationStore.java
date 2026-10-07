package org.hexa.core.chat.port.out;

import java.util.List;
import java.util.Optional;

import org.hexa.core.chat.domain.ChatConversation;

public interface IChatConversationStore {

    ChatConversation save(ChatConversation conversation);

    Optional<ChatConversation> findById(Long id);

    /** Per la sidebar: piu' di recente attive prima. */
    List<ChatConversation> findAllByRecency();

    Optional<ChatConversation> findMostRecent();

    List<ChatConversation> findAll();

    void delete(ChatConversation conversation);
}
