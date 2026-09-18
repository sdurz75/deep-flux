package org.dual.replicate.service;

import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

/**
 * Spike per verificare l'integrazione del Web Component &lt;deep-chat&gt;
 * col resto dell'architettura: chat libera, senza persistenza, sullo
 * stesso ChatClient (Spring AI su OpenRouter) gia' usato da ChatService.
 * La cronologia vive solo nel browser: deep-chat la rimanda per intero
 * ad ogni turno (vedi requestBodyLimits in templates/deep-chat.html).
 */
@Service
public class DeepChatService {

    private final ChatClient chatClient;

    public DeepChatService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder
                .defaultSystem("You are a helpful, friendly assistant.")
                .build();
    }

    public String reply(List<Turn> history) {
        List<Message> messages = history.stream()
                .map(turn -> (Message) ("ai".equals(turn.role())
                        ? new AssistantMessage(turn.text())
                        : new UserMessage(turn.text())))
                .toList();
        return chatClient.prompt().messages(messages).call().content();
    }

    /** Un turno cosi' come lo manda/vuole deep-chat: role "user" o "ai". */
    public record Turn(String role, String text) {
    }
}
