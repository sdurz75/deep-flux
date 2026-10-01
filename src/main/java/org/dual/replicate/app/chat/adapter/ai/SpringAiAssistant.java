package org.dual.replicate.app.chat.adapter.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.dual.replicate.app.OpenRouterCalls;
import org.dual.replicate.app.chat.domain.AssistantException;
import org.dual.replicate.app.chat.domain.ChatReply;
import org.dual.replicate.app.chat.domain.ChatTurn;
import org.dual.replicate.app.chat.port.out.IAssistant;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * L'assistente di /deep-chat sul {@code ChatClient} di Spring AI (OpenRouter). Il modello ha tre tool (WebSearchTool via SearXNG,
 * ImageGenerationTool via Replicate, ArchiveSearchTool se la ricerca semantica e' attiva) che decide da solo se e quando usare.
 * Qui sta tutto cio' che e' Spring AI: il system prompt, i tool, il {@code ToolContext} (modello e parametri scelti nella UI, e il
 * canale {@link GenerationResultHolder} da cui escono gli id delle generazioni avviate dal tool).
 */
@Component
class SpringAiAssistant implements IAssistant {

    private static final Logger log = LoggerFactory.getLogger(SpringAiAssistant.class);

    private final ChatClient chatClient;
    private final Messages i18n;

    SpringAiAssistant(ChatClient.Builder chatClientBuilder,
                      WebSearchTool webSearchTool,
                      ImageGenerationTool imageGenerationTool,
                      Optional<ArchiveSearchTool> archiveSearchTool,
                      Messages i18n,
                      @Value("${prompts.creative-context}") String creativeContext,
                      @Value("${deep-chat.image-prompting-guide}") String imagePromptingGuide) {
        this.i18n = i18n;
        this.chatClient = chatClientBuilder
                .defaultSystem("""
                        You are a helpful, friendly assistant. You can search the public web
                        with the searchWeb tool whenever a question needs current information
                        or facts you may not know. You can search the user's own archive of past
                        generations and conversations by meaning with the searchArchive tool
                        (when the user refers to something made or discussed before). You can also generate images with the
                        generateImage tool: it always uses the model currently selected in the
                        UI (you are told which one in a system note), there is no way to pick a
                        different one for it - if the user asks for a different model, tell them
                        to change the selection in the UI first.

                        Never call generateImage right after the user's first mention of
                        wanting an image. First discuss and refine what they want - subject,
                        style, setting, mood, anything relevant - asking clarifying questions
                        if the request is vague, and propose the actual prompt you intend to
                        use. Only call generateImage once the user has clearly confirmed that
                        exact prompt (e.g. "yes", "go", "generate it", or an explicit edit
                        they then approve) - never on an implicit go-ahead you inferred
                        yourself. If they change their mind before confirming, update the
                        proposal and ask again rather than generating.

                        """ + creativeContext + "\n\n" + imagePromptingGuide)
                // searchArchive solo con la ricerca semantica attiva (app.search.enabled).
                .defaultTools(Stream.concat(Stream.of(webSearchTool, imageGenerationTool), archiveSearchTool.stream()).toArray())
                .build();
    }

    @Override
    public ChatReply respond(List<ChatTurn> history, String selectedModel, Map<String, Object> generationParameters) {
        GenerationResultHolder resultHolder = new GenerationResultHolder();
        Instant start = Instant.now();
        try {
            List<Message> messages = buildMessages(history, selectedModel);
            Map<String, Object> toolContext = new HashMap<>();
            toolContext.put(GenerationResultHolder.CONTEXT_KEY, resultHolder);
            toolContext.put(ImageGenerationTool.PARAMETERS_CONTEXT_KEY, generationParameters);
            toolContext.put(ImageGenerationTool.MODEL_CONTEXT_KEY, selectedModel);

            ChatResponse chatResponse;
            try {
                chatResponse = OpenRouterCalls.CALLER.call("chatTurn", () -> chatClient.prompt()
                        .messages(messages)
                        .toolContext(toolContext)
                        .call()
                        .chatResponse());
            } catch (RuntimeException e) {
                log.warn("Chiamata al modello LLM remoto (OpenRouter) fallita dopo {} ms: {}",
                        Duration.between(start, Instant.now()).toMillis(), e.getMessage());
                throw e;
            }
            Duration elapsed = Duration.between(start, Instant.now());
            if (chatResponse == null || chatResponse.getResult() == null
                    || chatResponse.getResult().getOutput() == null
                    || chatResponse.getResult().getOutput().getText() == null) {
                throw new IllegalStateException(i18n.get("deepchat.error.llmEmptyResult"));
            }
            logChatResponse(chatResponse, elapsed);
            return new ChatReply(chatResponse.getResult().getOutput().getText(),
                    List.copyOf(resultHolder.getStartedGenerationIds()));
        } catch (RuntimeException e) {
            // Le generazioni gia' avviate dal tool devono avere comunque il loro watcher: viaggiano con l'eccezione.
            throw new AssistantException(e, resultHolder.getStartedGenerationIds());
        }
    }

    /**
     * Traccia i dati salienti di ogni turno col modello LLM remoto (OpenRouter): modello effettivo, token consumati, motivo di
     * chiusura della risposta, durata. A INFO, non DEBUG: sono dati operativi (costo/uso) che servono anche fuori da una sessione
     * di debug attiva.
     */
    private void logChatResponse(ChatResponse chatResponse, Duration elapsed) {
        var metadata = chatResponse.getMetadata();
        Usage usage = metadata.getUsage();
        String finishReason = chatResponse.getResult() != null
                ? chatResponse.getResult().getMetadata().getFinishReason()
                : null;
        log.info("Turno LLM completato: modello={}, tokenPrompt={}, tokenCompletion={}, tokenTotale={}, "
                        + "finishReason={}, durataMs={}",
                metadata.getModel(),
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens(),
                usage == null ? null : usage.getTotalTokens(),
                finishReason,
                elapsed.toMillis());
    }

    private static List<Message> buildMessages(List<ChatTurn> history, String selectedModel) {
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
}
