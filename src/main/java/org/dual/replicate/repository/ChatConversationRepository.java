package org.dual.replicate.repository;

import java.util.List;

import org.dual.replicate.domain.ChatConversation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatConversationRepository extends JpaRepository<ChatConversation, Long> {

    /** Usata dall'elenco conversazioni: le piu' recenti prima. */
    List<ChatConversation> findTop20ByOrderByCreatedAtDesc();
}
