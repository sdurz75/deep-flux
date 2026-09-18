package org.dual.replicate.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.dual.replicate.domain.Generation;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Orchestrazione del Web Component &lt;deep-chat&gt;: chat libera, senza
 * persistenza (la cronologia vive solo nel browser, deep-chat la rimanda
 * per intero ad ogni turno, vedi requestBodyLimits in
 * templates/deep-chat.html), sul ChatClient di Spring AI (OpenRouter).
 * Il modello ha due tool (WebSearchTool via SearXNG, ImageGenerationTool
 * via Replicate) che decide da solo se e quando usare.
 */
@Service
public class DeepChatService {

    private final ChatClient chatClient;

    public DeepChatService(ChatClient.Builder chatClientBuilder,
                            WebSearchTool webSearchTool,
                            ImageGenerationTool imageGenerationTool,
                            @Value("${deep-chat.image-prompting-guide}") String imagePromptingGuide) {
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

        GenerationResultHolder resultHolder = new GenerationResultHolder();
        String text = chatClient.prompt()
                .messages(messages)
                .toolContext(Map.of(GenerationResultHolder.CONTEXT_KEY, resultHolder))
                .call()
                .content();

        return new Reply(text, resultHolder.getGeneration());
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
