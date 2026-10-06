package org.dual.replicate.core.ai.adapter.ai;

import org.dual.replicate.core.ai.port.in.IAiCalls;
import org.dual.replicate.core.ai.port.out.IPromptModel;
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
    private final IAiCalls aiCalls;

    ChatClientPromptModel(ChatClient.Builder chatClientBuilder, IAiCalls aiCalls) {
        this.chatClient = chatClientBuilder.build();
        this.aiCalls = aiCalls;
    }

    @Override
    public String complete(String operation, String system, String user, String model, SourceImage image) {
        return aiCalls.call(operation, () -> {
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
