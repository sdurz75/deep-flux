package org.dual.replicate.repository;

import java.util.Collection;
import java.util.List;

import org.dual.replicate.domain.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /** True se una generazione ha gia' un turno in chat (idempotenza di DeepChatGenerationWatcher#persistOutcome). */
    boolean existsByGenerationId(Long generationId);

    /** Cronologia di una conversazione, in ordine cronologico: usata per ripristinarla al caricamento di /deep-chat/{id}. */
    List<ChatMessage> findByConversationIdOrderByIdAsc(Long conversationId);

    /** Fra gli id dati, quelli che hanno gia' un turno in chat (sweep di ChatRecoveryService). */
    @Query("select m.generationId from ChatMessage m where m.generationId in :ids")
    List<Long> findGenerationIdsIn(@Param("ids") Collection<Long> ids);
}
