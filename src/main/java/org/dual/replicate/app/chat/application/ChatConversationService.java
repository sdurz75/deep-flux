package org.dual.replicate.app.chat.application;

import java.util.List;
import java.util.Optional;

import org.dual.replicate.app.chat.domain.ChatConversation;
import org.dual.replicate.app.chat.domain.ChatMessage;
import org.dual.replicate.app.chat.port.in.IChatConversations;
import org.dual.replicate.app.chat.port.out.IChatConversationStore;
import org.dual.replicate.app.chat.port.out.IChatMessageStore;
import org.dual.replicate.app.generation.port.in.IGenerations;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD delle conversazioni di /deep-chat (creazione, rinomina,
 * cancellazione dalla sidebar). Volutamente separata da ChatService,
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
public class ChatConversationService implements IChatConversations {

    /** VARCHAR(255) in CHAT_CONVERSATION (vedi V5__chat_conversations.sql): un titolo piu' lungo va troncato, non lasciato rompere la save() con un errore SQL. */
    private static final int MAX_TITLE_LENGTH = 255;

    private final IChatConversationStore conversationRepository;
    private final IChatMessageStore chatMessageRepository;
    private final IGenerations generations;
    private final Messages messages;

    public ChatConversationService(IChatConversationStore conversationRepository,
                                    IChatMessageStore chatMessageRepository,
                                    IGenerations generations,
                                    Messages messages) {
        this.generations = generations;
        this.conversationRepository = conversationRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.messages = messages;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatConversation> list() {
        return conversationRepository.findAllByRecency();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ChatConversation> find(Long id) {
        return conversationRepository.findById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessage> history(Long conversationId) {
        return chatMessageRepository.findByConversation(conversationId);
    }

    /** Conversazione piu' di recente attiva, o una nuova vuota se non ne esiste ancora nessuna (primo avvio). */
    @Override
    public ChatConversation resolveDefault() {
        return conversationRepository.findMostRecent()
                .orElseGet(this::create);
    }

    @Override
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
     * una riga senza testo, e {@code ChatService.persistLatestUserTurn}
     * non deriverebbe piu' un titolo dal primo turno (il suo controllo
     * e' proprio {@code title == null}).
     */
    @Override
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
     * poi la riga stessa. Le Generation non si cancellano: appartengono
     * al registro globale della galleria, ciclo di vita indipendente da
     * quello della conversazione (vedi CLAUDE.md, Scopo); si scollegano
     * soltanto (IGenerations#detachFromConversation: lo schema di
     * generation non ha FK verso la chat).
     */
    @Override
    public void delete(Long id) {
        ChatConversation conversation = getOrThrow(id);
        chatMessageRepository.deleteAll(chatMessageRepository.findByConversation(id));
        generations.detachFromConversation(id);
        conversationRepository.delete(conversation);
    }

    private ChatConversation getOrThrow(Long id) {
        return conversationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(messages.get("deepchat.error.conversationNotFound")));
    }
}
