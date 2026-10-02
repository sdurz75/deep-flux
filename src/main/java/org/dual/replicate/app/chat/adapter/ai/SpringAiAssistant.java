package org.dual.replicate.app.chat.adapter.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
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
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * L'assistente di /deep-chat sul {@code ChatClient} di Spring AI (OpenRouter). Il modello ha i tool WebSearchTool (SearXNG),
 * ImageGenerationTool (Replicate), LibraryTool (sola lettura sull'app), FavouriteTool (mutazione leggera), ActionProposalTool (proposte con bottone, mai eseguite) e, se la ricerca semantica e'
 * attiva, ArchiveSearchTool e NoteTool: decide da solo se e quando usarli.
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
                      LibraryTool libraryTool,
                      FavouriteTool favouriteTool,
                      ActionProposalTool actionProposalTool,
                      Optional<ArchiveSearchTool> archiveSearchTool,
                      Optional<NoteTool> noteTool,
                      Messages i18n,
                      Environment env) {
        this.i18n = i18n;
        // Il system prompt e' assemblato a sezioni (prompts.properties): il nucleo c'e' sempre, la sezione di un gruppo di tool solo
        // se il gruppo e' registrato (searchArchive solo con la ricerca semantica attiva, app.search.enabled).
        String systemPrompt = Stream.of(
                        env.getRequiredProperty("deep-chat.section.core"),
                        env.getRequiredProperty("deep-chat.section.guidance"),
                        env.getRequiredProperty("deep-chat.section.web"),
                        env.getRequiredProperty("deep-chat.section.library"),
                        archiveSearchTool.isPresent() ? env.getRequiredProperty("deep-chat.section.archive") : null,
                        env.getRequiredProperty("deep-chat.section.edit"),
                        env.getRequiredProperty("deep-chat.section.actions"),
                        noteTool.isPresent() ? env.getRequiredProperty("deep-chat.section.notes") : null,
                        env.getRequiredProperty("deep-chat.section.generation"),
                        env.getRequiredProperty("prompts.creative-context"),
                        env.getRequiredProperty("deep-chat.image-prompting-guide"))
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n\n"));
        this.chatClient = chatClientBuilder
                .defaultSystem(systemPrompt)
                .defaultTools(Stream.of(Stream.of(webSearchTool, imageGenerationTool, libraryTool, favouriteTool, actionProposalTool), archiveSearchTool.stream(), noteTool.stream())
                        .flatMap(tools -> tools).toArray())
                .build();
    }

    @Override
    public ChatReply respond(Long conversationId, List<ChatTurn> history, String selectedModel, Map<String, Object> generationParameters) {
        GenerationResultHolder resultHolder = new GenerationResultHolder();
        ActionProposalHolder proposals = new ActionProposalHolder();
        Instant start = Instant.now();
        try {
            List<Message> messages = buildMessages(history, selectedModel);
            Map<String, Object> toolContext = new HashMap<>();
            toolContext.put(GenerationResultHolder.CONTEXT_KEY, resultHolder);
            toolContext.put(ActionProposalHolder.CONTEXT_KEY, proposals);
            if (conversationId != null) {
                toolContext.put(LibraryTool.CONVERSATION_ID_CONTEXT_KEY, conversationId);
            }
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
                    List.copyOf(resultHolder.getStartedGenerationIds()), List.copyOf(proposals.getActions()));
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
                    "Image generation model currently selected in the UI: " + selectedModel));
        }
        history.forEach(turn -> messages.add("ai".equals(turn.role())
                ? new AssistantMessage(turn.text())
                : new UserMessage(turn.text())));
        return messages;
    }
}
