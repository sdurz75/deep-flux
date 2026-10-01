package org.dual.replicate.app.prompt.adapter.ai;

import org.dual.replicate.app.OpenRouterCalls;
import org.dual.replicate.app.prompt.port.out.IPromptModel;
import org.dual.replicate.core.storage.domain.SourceImage;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;

/**
 * {@link IPromptModel} su Spring AI. {@link ChatClient.Builder} e' prototype-scoped (verificato in ChatClientAutoConfiguration di
 * spring-ai-autoconfigure-model-chat-client): questa istanza, costruita senza {@code defaultTools(...)}, non puo' in alcun modo
 * invocare i tool della chat - nessun rischio di avviare una generazione Replicate (spesa reale) da una richiesta di riscrittura.
 */
@Component
class ChatClientPromptModel implements IPromptModel {

    private final ChatClient chatClient;

    ChatClientPromptModel(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public String complete(String operation, String system, String user, String model, SourceImage image) {
        return OpenRouterCalls.CALLER.call(operation, () -> {
            var spec = chatClient.prompt().system(system);
            if (model != null) {
                spec = spec.options(OpenAiChatOptions.builder().model(model));
            }
            if (image == null) {
                return spec.user(user).call().content();
            }
            return spec.user(u -> u.text(user).media(MimeType.valueOf(image.mimeType()), new ByteArrayResource(image.bytes())))
                    .call().content();
        });
    }
}
