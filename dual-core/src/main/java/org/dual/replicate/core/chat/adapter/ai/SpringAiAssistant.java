package org.dual.replicate.core.chat.adapter.ai;

import org.dual.replicate.core.chat.port.in.IChatToolkit;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.dual.replicate.core.ai.port.in.IAiCalls;
import org.dual.replicate.core.chat.domain.AssistantException;
import org.dual.replicate.core.chat.domain.ChatReply;
import org.dual.replicate.core.chat.domain.ChatTurnContext;
import org.dual.replicate.core.chat.domain.ChatTurnResult;
import org.dual.replicate.core.chat.domain.ChatTurn;
import org.dual.replicate.core.chat.port.out.IAssistant;
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
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * L'assistente di /deep-chat sul {@code ChatClient} di Spring AI (OpenRouter). I tool sono quelli di ogni {@code IChatToolkit} presente
 * nel contesto (ricerca web, generazione, libreria, archivio, azioni proposte, note...): l'assistente non li elenca, li riceve, e con
 * loro la sezione di system prompt che li spiega. Il modello decide da solo se e quando usarli.
 * Qui sta tutto cio' che e' Spring AI: il system prompt, i tool, il {@code ToolContext} (il contesto del turno composto dall'host, piu' lo
 * stato per turno che ogni toolkit aggiunge in {@code beginTurn} e raccoglie in {@code endTurn}).
 */
@Component
class SpringAiAssistant implements IAssistant {

    private static final Logger log = LoggerFactory.getLogger(SpringAiAssistant.class);

    private final ChatClient chatClient;
    private final List<IChatToolkit> toolkits;
    private final Messages i18n;
    private final IAiCalls aiCalls;

    SpringAiAssistant(ChatClient.Builder chatClientBuilder, List<IChatToolkit> toolkits, Messages i18n, Environment env, IAiCalls aiCalls) {
        this.i18n = i18n;
        this.aiCalls = aiCalls;
        this.toolkits = List.copyOf(toolkits);
        // Il system prompt e' assemblato a sezioni (prompts.properties): quelle iniziali (nucleo, guida alla scoperta, mappa dell'app) e finali
        // (contesto creativo, guida ai prompt immagine) sono configurazione dell'host (app.chat.prompt-sections.leading/trailing: senza,
        // solo nucleo e guida), in mezzo la sezione di ogni gruppo di tool PRESENTE (nell'ordine di @Order; un gruppo condizionale, come
        // searchArchive con app.search.enabled, se manca non porta la sua).
        Binder binder = Binder.get(env);
        List<String> leading = binder.bind("app.chat.prompt-sections.leading", Bindable.listOf(String.class))
                .orElse(List.of("deep-chat.section.core", "deep-chat.section.guidance"));
        List<String> trailing = binder.bind("app.chat.prompt-sections.trailing", Bindable.listOf(String.class)).orElse(List.of());
        String systemPrompt = Stream.of(
                        leading.stream(),
                        toolkits.stream().map(IChatToolkit::promptSection).filter(Objects::nonNull),
                        trailing.stream())
                .flatMap(keys -> keys)
                .map(env::getRequiredProperty)
                .collect(Collectors.joining("\n\n"));
        this.chatClient = chatClientBuilder
                .defaultSystem(systemPrompt)
                .defaultTools(toolkits.toArray())
                .build();
    }

    @Override
    public ChatReply respond(Long conversationId, List<ChatTurn> history, Map<String, Object> turnContext) {
        ChatTurnResult result = new ChatTurnResult();
        Map<String, Object> toolContext = new HashMap<>(turnContext);
        Instant start = Instant.now();
        try {
            List<String> notes = toolContext.remove(ChatTurnContext.SYSTEM_NOTES) instanceof List<?> list
                    ? list.stream().map(String::valueOf).toList() : List.of();
            List<Message> messages = buildMessages(history, notes);
            if (conversationId != null) {
                toolContext.put(ChatTurnContext.CONVERSATION_ID, conversationId);
            }
            toolkits.forEach(toolkit -> toolkit.beginTurn(toolContext));

            ChatResponse chatResponse;
            try {
                chatResponse = aiCalls.call("chatTurn", () -> chatClient.prompt()
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
            toolkits.forEach(toolkit -> toolkit.endTurn(toolContext, result));
            return new ChatReply(chatResponse.getResult().getOutput().getText(), result.startedOutcomeRefs(), result.extras());
        } catch (RuntimeException e) {
            // Gli esiti gia' avviati dai tool devono avere comunque il loro watcher: viaggiano con l'eccezione (endTurn e' idempotente).
            toolkits.forEach(toolkit -> toolkit.endTurn(toolContext, result));
            throw new AssistantException(e, result.startedOutcomeRefs());
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

    /** I turni "system" sono le note dell'app per il modello (l'esito di una generazione): non sono ne' dell'utente ne' sue risposte. */
    static List<Message> buildMessages(List<ChatTurn> history, List<String> systemNotes) {
        List<Message> messages = new ArrayList<>();
        systemNotes.forEach(note -> messages.add(new SystemMessage(note)));
        history.forEach(turn -> messages.add(switch (turn.role()) {
            case ChatTurn.AI -> new AssistantMessage(turn.text());
            case ChatTurn.SYSTEM -> new SystemMessage(turn.text());
            default -> new UserMessage(turn.text());
        }));
        return messages;
    }
}
