package org.dual.replicate.service;

import org.dual.replicate.domain.ChatConversation;
import org.dual.replicate.i18n.Messages;
import org.dual.replicate.repository.ChatConversationRepository;
import org.dual.replicate.repository.ChatMessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD delle conversazioni di /deep-chat (creazione, rinomina,
 * cancellazione dalla sidebar). Volutamente separata da DeepChatService,
 * che invece orchestra la chiamata al modello LLM remoto (OpenRouter):
 * quel metodo (reply()) non e' @Transactional apposta, per non tenere
 * aperta una connessione DB per tutta la durata di una chiamata esterna
 * lenta, mentre qui ogni operazione e' un'unita' breve e atomica che
 * merita @Transactional — mescolare le due responsabilita' nella stessa
 * classe avrebbe forzato un compromesso sbagliato in un verso o
 * nell'altro.
 */
@Service
@Transactional
public class ChatConversationService {

    /** VARCHAR(255) in CHAT_CONVERSATION (vedi V5__chat_conversations.sql): un titolo piu' lungo va troncato, non lasciato rompere la save() con un errore SQL. */
    private static final int MAX_TITLE_LENGTH = 255;

    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final Messages messages;

    public ChatConversationService(ChatConversationRepository conversationRepository,
                                    ChatMessageRepository chatMessageRepository,
                                    Messages messages) {
        this.conversationRepository = conversationRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.messages = messages;
    }

    /** Conversazione piu' di recente attiva, o una nuova vuota se non ne esiste ancora nessuna (primo avvio). */
    public ChatConversation resolveDefault() {
        return conversationRepository.findFirstByOrderByUpdatedAtDesc()
                .orElseGet(this::create);
    }

    public ChatConversation create() {
        return conversationRepository.save(new ChatConversation());
    }

    /**
     * Non chiama touch(): rinominare non e' attivita' di chat, non deve
     * riordinare la sidebar per recenza. Un titolo vuoto/di soli spazi
     * (rinomina lasciata in bianco dall'utente in UI) va normalizzato a
     * null, non salvato come stringa vuota: altrimenti l'Elvis
     * `${c.title} ?: #{deepChat.conversation.untitled}` nel template
     * smetterebbe di scattare (una stringa vuota non e' null) mostrando
     * una riga senza testo, e {@code DeepChatService.persistLatestUserTurn}
     * non deriverebbe piu' un titolo dal primo turno (il suo controllo
     * e' proprio {@code title == null}).
     */
    public ChatConversation rename(Long id, String title) {
        ChatConversation conversation = getOrThrow(id);
        conversation.setTitle(normalizeTitle(title));
        return conversationRepository.save(conversation);
    }

    private static String normalizeTitle(String title) {
        if (title == null) {
            return null;
        }
        String trimmed = title.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= MAX_TITLE_LENGTH ? trimmed : trimmed.substring(0, MAX_TITLE_LENGTH);
    }

    /**
     * Cancella prima i turni della conversazione (vincolo FK_CHAT_MESSAGE_CONVERSATION),
     * poi la riga stessa. Non tocca le Generation referenziate da quei
     * turni: appartengono al registro globale della galleria, ciclo di
     * vita indipendente da quello della conversazione (vedi CLAUDE.md,
     * Scopo).
     */
    public void delete(Long id) {
        ChatConversation conversation = getOrThrow(id);
        chatMessageRepository.deleteAll(chatMessageRepository.findByConversationIdOrderByIdAsc(id));
        conversationRepository.delete(conversation);
    }

    private ChatConversation getOrThrow(Long id) {
        return conversationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(messages.get("deepchat.error.conversationNotFound")));
    }
}
