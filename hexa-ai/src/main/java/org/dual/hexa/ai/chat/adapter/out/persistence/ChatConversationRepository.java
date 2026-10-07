package org.dual.hexa.ai.chat.adapter.out.persistence;

import java.util.List;
import java.util.Optional;

import org.dual.hexa.ai.chat.domain.ChatConversation;
import org.springframework.data.jpa.repository.JpaRepository;

interface ChatConversationRepository extends JpaRepository<ChatConversation, Long> {

    /** Elenco per la sidebar: piu' di recente attive prima. */
    List<ChatConversation> findAllByOrderByUpdatedAtDesc();

    /** Risolve la conversazione "di default" (redirect da /deep-chat senza id): la piu' di recente attiva. */
    Optional<ChatConversation> findFirstByOrderByUpdatedAtDesc();
}
