package org.dual.replicate.repository;

import java.util.List;

import org.dual.replicate.domain.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /** Cronologia completa, in ordine cronologico: usata per ripristinare la chat al caricamento di /deep-chat. */
    List<ChatMessage> findAllByOrderByIdAsc();
}
