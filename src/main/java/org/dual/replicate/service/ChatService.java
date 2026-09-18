package org.dual.replicate.service;

import java.time.Instant;
import java.util.List;

import org.dual.replicate.domain.ChatConversation;
import org.dual.replicate.domain.ChatMessage;
import org.dual.replicate.domain.ChatRole;
import org.dual.replicate.repository.ChatConversationRepository;
import org.dual.replicate.repository.ChatMessageRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

/**
 * Orchestrazione della chat: persiste i turni su H2 e delega la
 * generazione della risposta al {@link ChatClient} configurato da Spring
 * AI (autoconfigurato dallo starter OpenAI, puntato su OpenRouter via
 * application.yml). Nessun tool-calling: l'assistente aiuta solo a
 * rifinire il prompt, la generazione immagine resta un passo esplicito
 * lanciato dall'utente su GenerationController.
 */
@Service
public class ChatService {

    private static final String SYSTEM_PROMPT = """
            You are an assistant that helps a user write effective prompts for
            AI image generation models. Ask clarifying questions if useful and,
            once you have enough detail, propose a clear, self-contained final
            prompt in English, ready to be used as-is.""";

    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatClient chatClient;

    public ChatService(ChatConversationRepository conversationRepository,
                        ChatMessageRepository messageRepository,
                        ChatClient.Builder chatClientBuilder) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.chatClient = chatClientBuilder.defaultSystem(SYSTEM_PROMPT).build();
    }

    public ChatConversation startConversation() {
        return conversationRepository.save(new ChatConversation(Instant.now()));
    }

    public List<ChatConversation> listConversations() {
        return conversationRepository.findTop20ByOrderByCreatedAtDesc();
    }

    public ChatConversation getConversation(Long id) {
        return conversationRepository.findById(id)
                .orElseThrow(() -> new ChatException("Conversazione non trovata: " + id));
    }

    public List<ChatMessage> getMessages(Long conversationId) {
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
    }

    /**
     * Aggiunge il messaggio utente, chiama il modello con l'intera
     * cronologia e persiste la risposta. In caso di errore verso
     * OpenRouter il messaggio utente resta salvato (l'utente puo'
     * riprovare) ma nessuna risposta fittizia viene aggiunta.
     */
    public List<ChatMessage> sendMessage(Long conversationId, String userText) {
        ChatConversation conversation = getConversation(conversationId);

        messageRepository.save(new ChatMessage(conversation, ChatRole.USER, userText, Instant.now()));

        List<ChatMessage> history = getMessages(conversationId);
        List<Message> aiMessages = history.stream()
                .map(m -> (Message) (m.getRole() == ChatRole.USER
                        ? new UserMessage(m.getContent())
                        : new AssistantMessage(m.getContent())))
                .toList();

        String replyText;
        try {
            replyText = chatClient.prompt().messages(aiMessages).call().content();
        } catch (Exception e) {
            throw new ChatException("Errore nel contattare l'assistente via OpenRouter: " + e.getMessage(), e);
        }

        if (replyText == null || replyText.isBlank()) {
            throw new ChatException("L'assistente ha risposto senza contenuto utilizzabile.");
        }

        messageRepository.save(new ChatMessage(conversation, ChatRole.ASSISTANT, replyText, Instant.now()));
        return getMessages(conversationId);
    }
}
