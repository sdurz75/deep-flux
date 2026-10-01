package org.dual.replicate.app.chat.port.out;

import java.util.Collection;
import java.util.List;

import org.dual.replicate.app.chat.domain.ChatMessage;

public interface IChatMessageStore {

    ChatMessage save(ChatMessage message);

    /** {@code true} se una generazione ha gia' un turno in chat (idempotenza della scrittura dell'esito). */
    boolean existsByGenerationId(Long generationId);

    /** La cronologia di una conversazione, in ordine cronologico. */
    List<ChatMessage> findByConversation(Long conversationId);

    /** Fra gli id dati, quelli che hanno gia' un turno in chat. */
    List<Long> generationIdsWithTurn(Collection<Long> generationIds);

    void deleteAll(List<ChatMessage> messages);

    List<ChatMessage> findAll();
}
