package org.dual.replicate.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Riscrittura one-shot di una bozza di prompt (anche in italiano) in un
 * prompt Flux ben formato in inglese, per l'icona "AI enhance" accanto
 * alla textarea di /generations/new (fragments/generate-form.html ::
 * promptField, GenerationController#enhancePrompt). A differenza di
 * DeepChatService non c'e' conversazione ne' tool: {@link ChatClient.Builder}
 * e' prototype-scoped (verificato in ChatClientAutoConfiguration di
 * spring-ai-autoconfigure-model-chat-client), quindi questa istanza,
 * costruita senza defaultTools(...), non puo' in alcun modo invocare
 * ImageGenerationTool - nessun rischio di avviare una generazione Replicate
 * (spesa reale) da una semplice richiesta di riscrittura testo.
 */
@Service
public class PromptEnhancementService {

    private final ChatClient chatClient;

    public PromptEnhancementService(ChatClient.Builder chatClientBuilder,
                                     @Value("${generateForm.prompt-enhancement-guide}") String promptEnhancementGuide) {
        this.chatClient = chatClientBuilder.defaultSystem(promptEnhancementGuide).build();
    }

    public String enhance(String draftPrompt) {
        String result = chatClient.prompt().user(draftPrompt).call().content();
        return result == null ? "" : result.trim();
    }
}
