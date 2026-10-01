package org.dual.replicate.app.chat.port.in;

import java.util.List;
import java.util.Optional;

import org.dual.replicate.app.chat.domain.ChatConversation;
import org.dual.replicate.app.chat.domain.ChatMessage;

/** Le conversazioni di /deep-chat: elenco, creazione, rinomina, cancellazione, cronologia. */
public interface IChatConversations {

    /** Piu' di recente attive prima. */
    List<ChatConversation> list();

    Optional<ChatConversation> find(Long id);

    /** La piu' di recente attiva, o una nuova vuota se non ne esiste ancora nessuna (primo avvio). */
    ChatConversation resolveDefault();

    ChatConversation create();

    /** Un titolo vuoto/di soli spazi diventa {@code null} (la UI mostra allora il titolo di default). */
    ChatConversation rename(Long id, String title);

    /** Cancella i turni e la conversazione; non tocca le generazioni (ciclo di vita indipendente). */
    void delete(Long id);

    /** I turni persistiti, in ordine cronologico. */
    List<ChatMessage> history(Long conversationId);
}
