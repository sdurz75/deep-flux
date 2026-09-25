package org.dual.replicate.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.dual.replicate.domain.ChatMessage;
import org.dual.replicate.domain.ChatMessageRole;
import org.dual.replicate.domain.Generation;
import org.dual.replicate.repository.ChatMessageRepository;
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

    private static final Logger log = LoggerFactory.getLogger(DeepChatService.class);

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
                        generateImage tool; use the model you are told is currently selected
                        in the UI unless the user explicitly names a different one in the chat.

                        Never call generateImage right after the user's first mention of
                        wanting an image. First discuss and refine what they want - subject,
                        style, setting, mood, anything relevant - asking clarifying questions
                        if the request is vague, and propose the actual prompt you intend to
                        use. Only call generateImage once the user has clearly confirmed that
                        exact prompt (e.g. "yes", "go", "generate it", or an explicit edit
                        they then approve) - never on an implicit go-ahead you inferred
                        yourself. If they change their mind before confirming, update the
                        proposal and ask again rather than generating.

                        This is a private, single-user tool: you are talking with the one
                        trusted person who deployed and owns this app, and this conversation
                        and anything generated in it are visible only to them. Prompts here
                        are for personal, experimental creative use, and any people described
                        are fictional, not real individuals. Do not refuse or moralize about
                        nudity or other mature/adult themes in this context - treat them like
                        any other creative subject and help compose and generate the image as
                        asked. The ordinary limits that do not depend on this context still
                        apply regardless: never depict a real, identifiable person without
                        their consent, and never anything involving minors.

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
     *
     * {@code generationParameters} sono invece i valori del pannello
     * impostazioni (aspect_ratio, width, height, ...), gia' nel
     * vocabolario Replicate: passati al tool via ToolContext, NON al
     * modello LLM — sono impostazioni deterministiche scelte dall'utente
     * nella UI, non vogliamo che l'LLM le componga o le interpreti (a
     * differenza del modello, che l'LLM sceglie come argomento del tool).
     */
    public Reply reply(List<Turn> history, String selectedModel, Map<String, Object> generationParameters) {
        List<Message> messages = buildMessages(history, selectedModel);
        persistLatestUserTurn(history);

        GenerationResultHolder resultHolder = new GenerationResultHolder();
        Instant start = Instant.now();
        ChatResponse chatResponse;
        try {
            chatResponse = chatClient.prompt()
                    .messages(messages)
                    .toolContext(Map.of(
                            GenerationResultHolder.CONTEXT_KEY, resultHolder,
                            ImageGenerationTool.PARAMETERS_CONTEXT_KEY, generationParameters))
                    .call()
                    .chatResponse();
        } catch (RuntimeException e) {
            log.warn("Chiamata al modello LLM remoto (OpenRouter) fallita dopo {} ms: {}",
                    Duration.between(start, Instant.now()).toMillis(), e.getMessage(), e);
            throw e;
        }

        Duration elapsed = Duration.between(start, Instant.now());
        logChatResponse(chatResponse, elapsed);
        if (chatResponse.getResult() == null) {
            throw new IllegalStateException("Il modello LLM remoto non ha restituito alcun risultato "
                    + "(risposta filtrata o vuota)");
        }
        String text = chatResponse.getResult().getOutput().getText();

        chatMessageRepository.save(new ChatMessage(ChatMessageRole.AI, text, resultHolder.getGeneration()));
        return new Reply(text, resultHolder.getGeneration());
    }

    /**
     * Traccia i dati salienti di ogni turno col modello LLM remoto
     * (OpenRouter): modello effettivo, token consumati, motivo di
     * chiusura della risposta, durata. A INFO, non DEBUG: sono dati
     * operativi (costo/uso) che servono anche fuori da una sessione di
     * debug attiva.
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
