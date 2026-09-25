package org.dual.replicate.repository;

import java.util.List;

import org.dual.replicate.domain.ChatMessage;
import org.dual.replicate.domain.Generation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /** Cronologia di una conversazione, in ordine cronologico: usata per ripristinarla al caricamento di /deep-chat/{id}. */
    List<ChatMessage> findByConversationIdOrderByIdAsc(Long conversationId);

    /**
     * Generazioni completate con successo prodotte in una conversazione,
     * in ordine cronologico: alimenta la galleria contestuale
     * dell'accordion di /deep-chat/{id}. Filtro su SUCCEEDED per lo
     * stesso motivo di {@code GenerationRepository.findByStatusOrderByCreatedAtDesc}:
     * {@code fragments/gallery-card.html :: card(...)} dereferenzia
     * {@code imageFilenames[0]} senza controlli, una riga pending/failed
     * andrebbe in errore.
     */
    @Query("select m.generation from ChatMessage m "
            + "where m.conversation.id = :conversationId "
            + "and m.generation is not null "
            + "and m.generation.status = org.dual.replicate.domain.GenerationStatus.SUCCEEDED "
            + "order by m.id asc")
    List<Generation> findSucceededGenerationsByConversationId(@Param("conversationId") Long conversationId);
}
