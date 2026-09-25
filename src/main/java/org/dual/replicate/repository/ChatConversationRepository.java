package org.dual.replicate.repository;

import java.util.List;
import java.util.Optional;

import org.dual.replicate.domain.ChatConversation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatConversationRepository extends JpaRepository<ChatConversation, Long> {

    /** Elenco per la sidebar: piu' di recente attive prima. */
    List<ChatConversation> findAllByOrderByUpdatedAtDesc();

    /** Risolve la conversazione "di default" (redirect da /deep-chat senza id): la piu' di recente attiva. */
    Optional<ChatConversation> findFirstByOrderByUpdatedAtDesc();
}
