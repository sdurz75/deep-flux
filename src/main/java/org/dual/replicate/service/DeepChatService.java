package org.dual.replicate.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.dual.replicate.domain.ChatMessage;
import org.dual.replicate.domain.ChatMessageRole;
import org.dual.replicate.domain.Generation;
import org.dual.replicate.repository.ChatMessageRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Orchestrazione del Web Component &lt;deep-chat&gt;: chat libera sul
 * ChatClient di Spring AI (OpenRouter). Il modello ha due tool
 * (WebSearchTool via SearXNG, ImageGenerationTool via Replicate) che
 * decide da solo se e quando usare.
 *
 * La cronologia e' persistita (ChatMessage/ChatMessageRepository, vedi
 * CLAUDE.md punto 3 dello Scopo) ma resta anche interamente lato client:
 * deep-chat la rimanda per intero ad ogni turno (vedi requestBodyLimits
 * in templates/deep-chat.html) ed e' quella che alimenta il modello
 * (buildMessages sotto) — la persistenza qui e' solo una copia durevole
 * per ripristinare la UI al prossimo caricamento di /deep-chat
 * (DeepChatController) e non rientra nel giro di richieste verso l'LLM.
 */
@Service
public class DeepChatService {

    private final ChatClient chatClient;
    private final ChatMessageRepository chatMessageRepository;

    public DeepChatService(ChatClient.Builder chatClientBuilder,
                            WebSearchTool webSearchTool,
                            ImageGenerationTool imageGenerationTool,
                            ChatMessageRepository chatMessageRepository,
                            @Value("${deep-chat.image-prompting-guide}") String imagePromptingGuide) {
        this.chatMessageRepository = chatMessageRepository;
        this.chatClient = chatClientBuilder
                .defaultSystem("""
                        You are a helpful, friendly assistant. You can search the public web
                        with the searchWeb tool whenever a question needs current information
                        or facts you may not know. You can also generate images with the
                        generateImage tool when the user asks for one; use the model you are
                        told is currently selected in the UI unless the user explicitly names
                        a different one in the chat.

                        """ + imagePromptingGuide)
                .defaultTools(webSearchTool, imageGenerationTool)
                .build();
    }

    /**
     * {@code selectedModel} e' il modello Replicate scelto nel combobox
     * lato UI (vedi templates/deep-chat.html), inviato dal client su ogni
     * turno tramite requestInterceptor: viene passato al modello come
     * nota di contesto, non imposto a livello di tool, cosi' l'utente
     * puo' comunque chiederne un altro esplicitamente in chat.
     */
    public Reply reply(List<Turn> history, String selectedModel) {
        List<Message> messages = buildMessages(history, selectedModel);
        persistLatestUserTurn(history);

        GenerationResultHolder resultHolder = new GenerationResultHolder();
        String text = chatClient.prompt()
                .messages(messages)
                .toolContext(Map.of(GenerationResultHolder.CONTEXT_KEY, resultHolder))
                .call()
                .content();

        chatMessageRepository.save(new ChatMessage(ChatMessageRole.AI, text, resultHolder.getGeneration()));
        return new Reply(text, resultHolder.getGeneration());
    }

    /**
     * Persiste solo l'ultimo turno della history mandata dal client (il
     * messaggio utente che ha innescato questa chiamata): i turni
     * precedenti sono gia' su DB dalle chiamate passate, deep-chat li
     * rimanda tutti ad ogni richiesta ma andrebbero salvati di nuovo.
     */
    private void persistLatestUserTurn(List<Turn> history) {
        if (history.isEmpty()) {
            return;
        }
        Turn latest = history.get(history.size() - 1);
        if ("user".equals(latest.role())) {
            chatMessageRepository.save(new ChatMessage(ChatMessageRole.USER, latest.text(), null));
        }
    }

    /** Azzera la cronologia persistita. Usata dal controllo di reset in UI (vedi DeepChatApiController). */
    public void resetHistory() {
        chatMessageRepository.deleteAllInBatch();
    }

    private List<Message> buildMessages(List<Turn> history, String selectedModel) {
        List<Message> messages = new ArrayList<>();
        if (selectedModel != null && !selectedModel.isBlank()) {
            messages.add(new SystemMessage(
                    "Modello di generazione immagini attualmente selezionato nella UI: " + selectedModel));
        }
        history.forEach(turn -> messages.add("ai".equals(turn.role())
                ? new AssistantMessage(turn.text())
                : new UserMessage(turn.text())));
        return messages;
    }

    /** Un turno cosi' come lo manda/vuole deep-chat: role "user" o "ai". */
    public record Turn(String role, String text) {
    }

    /** {@code image} e' non-null solo se in questo turno e' stata generata con successo un'immagine. */
    public record Reply(String text, Generation image) {
    }
}
