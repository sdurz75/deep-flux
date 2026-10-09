package org.dual.hexa.ai.chat.port.out;

import java.util.Collection;
import java.util.List;

import org.dual.hexa.ai.chat.domain.ChatMessage;

public interface IChatMessageStore {

    ChatMessage save(ChatMessage message);

    /** {@code true} se un esito (generazione) ha gia' un turno in chat (idempotenza della scrittura dell'esito). */
    boolean existsByOutcomeRef(Long outcomeRef);

    /** La cronologia di una conversazione, in ordine cronologico. */
    List<ChatMessage> findByConversation(Long conversationId);

    /** Fra gli id dati, quelli che hanno gia' un turno in chat. */
    List<Long> outcomeRefsWithTurn(Collection<Long> outcomeRefs);

    void deleteAll(List<ChatMessage> messages);

    List<ChatMessage> findAll();

    long count();
}
