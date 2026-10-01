package org.dual.replicate.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.dual.replicate.app.AppEventSource;
import org.dual.replicate.core.events.domain.CoreEventSource;
import org.dual.replicate.core.kernel.EventSource;
import org.dual.replicate.domain.ChatConversation;
import org.dual.replicate.domain.ChatMessage;
import org.dual.replicate.domain.ChatMessageRole;
import org.dual.replicate.domain.Generation;
import org.dual.replicate.i18n.Messages;
import org.dual.replicate.repository.ChatConversationRepository;
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
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

/**
 * Orchestrazione del Web Component &lt;deep-chat&gt;: chat libera sul
 * ChatClient di Spring AI (OpenRouter). Il modello ha due tool
 * (WebSearchTool via SearXNG, ImageGenerationTool via Replicate) che
 * decide da solo se e quando usare.
 *
 * /deep-chat supporta piu' conversazioni (ChatConversation), ognuna con
 * la propria cronologia (ChatMessage/ChatMessageRepository, vedi
 * CLAUDE.md punto 3 dello Scopo): {@link #reply} opera sempre su una
 * conversazione precisa, passata per id dal client. La cronologia resta
 * anche interamente lato client: deep-chat la rimanda per intero ad ogni
 * turno (vedi requestBodyLimits in templates/deep-chat.html) ed e' quella
 * che alimenta il modello (buildMessages sotto) — la persistenza qui e'
 * solo una copia durevole per ripristinare la UI al prossimo caricamento
 * di quella conversazione (DeepChatController) e non rientra nel giro di
 * richieste verso l'LLM. La gestione CRUD delle conversazioni stesse
 * (creazione/rinomina/cancellazione) vive invece in
 * ChatConversationService, non qui: vedi la sua javadoc per il perche'
 * della separazione.
 */
@Service
public class DeepChatService {

    private static final Logger log = LoggerFactory.getLogger(DeepChatService.class);

    /** Lunghezza massima del titolo auto-derivato dal primo turno utente di una conversazione, oltre la quale viene troncato. */
    private static final int TITLE_MAX_LENGTH = 60;

    private final ChatClient chatClient;
    private final ChatConversationRepository chatConversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final DeepChatGenerationWatcher generationWatcher;
    // Nome "i18n", non "messages": la classe usa gia' "messages" come
    // nome locale per List<Message> (i turni della conversazione, vedi
    // reply()/buildMessages()), collisione con Messages se chiamato
    // uguale.
    private final Messages i18n;
    private final SystemEventService systemEvents;

    public DeepChatService(ChatClient.Builder chatClientBuilder,
                            WebSearchTool webSearchTool,
                            ImageGenerationTool imageGenerationTool,
                            Optional<ArchiveSearchTool> archiveSearchTool,
                            ChatConversationRepository chatConversationRepository,
                            ChatMessageRepository chatMessageRepository,
                            DeepChatGenerationWatcher generationWatcher,
                            Messages i18n,
                            SystemEventService systemEvents,
                            @Value("${deep-chat.image-prompting-guide}") String imagePromptingGuide) {
        this.chatConversationRepository = chatConversationRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.generationWatcher = generationWatcher;
        this.i18n = i18n;
        this.systemEvents = systemEvents;
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
                // searchArchive solo con la ricerca semantica attiva (app.search.enabled).
                .defaultTools(Stream.concat(Stream.of(webSearchTool, imageGenerationTool), archiveSearchTool.stream()).toArray())
                .build();
    }

    /**
     * {@code conversationId} identifica la conversazione a cui questo
     * turno appartiene (scelta/gia' aperta lato UI, vedi
     * DeepChatController): risolta subito, prima della chiamata
     * all'LLM, cosi' un id sconosciuto o non piu' valido non spreca una
     * chiamata remota — l'eccezione risale al chiamante (DeepChatApiController),
     * che la traduce gia' genericamente in un messaggio d'errore in
     * chat, nessuna gestione dedicata necessaria qui.
     *
     * {@code selectedModel} e' il modello Replicate scelto nel combobox
     * lato UI (vedi templates/deep-chat.html), inviato dal client su ogni
     * turno tramite requestInterceptor: passato sia come nota di
     * contesto al modello LLM (buildMessages sotto, utile ad es. per
     * capire se e' uno dei LoRA personali dell'utente, vedi
     * imagePromptingGuide) sia al tool via ToolContext
     * (ImageGenerationTool.MODEL_CONTEXT_KEY) — per ora (vedi CLAUDE.md,
     * Scopo punto 1) e' sempre e solo questo il modello usato da
     * generateImage, non un parametro che l'LLM sceglie componendo la
     * chiamata al tool, stessa ragione di generationParameters sotto.
     *
     * {@code generationParameters} sono invece i valori del pannello
     * impostazioni (aspect_ratio, width, height, ...), gia' nel
     * vocabolario Replicate: passati al tool via ToolContext, NON al
     * modello LLM — sono impostazioni deterministiche scelte dall'utente
     * nella UI, non vogliamo che l'LLM le componga o le interpreti.
     */
    public Reply reply(Long conversationId, List<Turn> history, String selectedModel, Map<String, Object> generationParameters) {
        ChatConversation conversation = chatConversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException(i18n.get("deepchat.error.conversationNotFound")));

        List<Message> messages = buildMessages(history, selectedModel);
        persistLatestUserTurn(conversation, history);

        GenerationResultHolder resultHolder = new GenerationResultHolder();
        // Catturata nel thread della richiesta (l'unico con una locale
        // valorizzata da AcceptHeaderLocaleResolver): i watch avviati piu'
        // sotto girano su thread @Async, dove LocaleContextHolder e' vuoto
        // e cadrebbe sulla locale della JVM, non quella del browser.
        Locale locale = LocaleContextHolder.getLocale();
        Instant start = Instant.now();
        try {
            Map<String, Object> toolContext = new HashMap<>();
            toolContext.put(GenerationResultHolder.CONTEXT_KEY, resultHolder);
            toolContext.put(ImageGenerationTool.PARAMETERS_CONTEXT_KEY, generationParameters);
            toolContext.put(ImageGenerationTool.MODEL_CONTEXT_KEY, selectedModel);

            String text;
            try {
                ChatResponse chatResponse;
                try {
                    chatResponse = OpenRouterException.CALLER.call("chatTurn", () -> chatClient.prompt()
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
                text = chatResponse.getResult().getOutput().getText();
            } catch (RuntimeException e) {
                throw failTurn(conversation, AppEventSource.OPENROUTER, "chatTurn", e);
            }

            try {
                chatMessageRepository.save(new ChatMessage(conversation, ChatMessageRole.AI, text, null));
            } catch (RuntimeException e) {
                throw failTurn(conversation, CoreEventSource.INTERNAL, "saveChatTurn", e);
            }
            return new Reply(text, resultHolder.getStartedGenerationIds());
        } finally {
            // Nel finally PIU' ESTERNO, dopo aver salvato il turno AI (non
            // solo sul percorso di successo: una generazione gia' avviata
            // dal tool deve arrivare comunque via push anche se la
            // chiamata all'LLM fallisce dopo) — cosi' l'ordine cronologico/
            // di persistenza resta sempre corretto: un modello veloce
            // (es. flux-schnell) puo' finire prima che l'LLM produca il
            // testo del turno, il messaggio "immagine pronta" non deve mai
            // precedere quello del turno che l'ha avviata.
            for (Long id : resultHolder.getStartedGenerationIds()) {
                // Per id: un fallimento su uno non deve impedire agli altri di avere il watcher, ne'
                // mascherare l'eccezione originale del turno (il recupero riprende comunque le orfane).
                try {
                    generationWatcher.attachToConversation(id, conversation.getId());
                    generationWatcher.watch(id, conversation.getId(), locale);
                } catch (RuntimeException e) {
                    systemEvents.record(CoreEventSource.INTERNAL, "watchStart", e, id, conversation.getId());
                }
            }
        }
    }

    /**
     * Il turno e' fallito: registra l'errore (log + tabella + toast), scrive in cronologia un turno
     * ASSISTANT d'errore (il turno USER e' gia' salvato: senza, resterebbe orfano) e ritorna l'eccezione
     * da lanciare, gia' col messaggio per l'utente. Non lancia mai da se'.
     */
    private DeepChatFailedException failTurn(ChatConversation conversation, EventSource source, String operation, RuntimeException cause) {
        systemEvents.record(source, operation, cause, null, conversation.getId());
        String userMessage = i18n.get("deepchat.error.contactAssistant", SystemEventService.sanitize(cause));
        try {
            chatMessageRepository.save(ChatMessage.errorTurn(conversation, userMessage));
        } catch (RuntimeException e) {
            log.error("Impossibile salvare il turno d'errore in cronologia: {}", e.toString());
        }
        return new DeepChatFailedException(userMessage, cause);
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
     *
     * Se la conversazione non ha ancora un titolo, lo deriva (troncato)
     * da questo stesso turno — mai un letterale di default persistito,
     * vedi ChatConversation — e aggiorna updatedAt (touch): e' questo il
     * punto che riordina la sidebar per recenza, PRIMA della chiamata
     * all'LLM sotto, cosi' l'ordine si aggiorna anche se quella chiamata
     * fallisce.
     */
    private void persistLatestUserTurn(ChatConversation conversation, List<Turn> history) {
        if (history.isEmpty()) {
            return;
        }
        Turn latest = history.get(history.size() - 1);
        if (!"user".equals(latest.role())) {
            return;
        }
        if (conversation.getTitle() == null) {
            conversation.setTitle(truncateTitle(latest.text()));
        }
        conversation.touch();
        chatConversationRepository.save(conversation);
        chatMessageRepository.save(new ChatMessage(conversation, ChatMessageRole.USER, latest.text(), null));
    }

    private static String truncateTitle(String text) {
        String trimmed = text.strip();
        return trimmed.length() <= TITLE_MAX_LENGTH ? trimmed : trimmed.substring(0, TITLE_MAX_LENGTH) + "…";
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

    /**
     * Non porta piu' un'eventuale immagine: il tool ritorna subito, prima
     * che una generazione avviata in questo turno sia pronta (vedi
     * ImageGenerationTool/DeepChatGenerationWatcher) — arriva sempre in
     * un secondo momento via push SSE, mai nella risposta sincrona.
     */
    public record Reply(String text, List<Long> startedGenerationIds) {
    }

    /**
     * Vocabolario JSON di deep-chat per un allegato: {@code type} e'
     * sempre "image", il solo che deep-chat riconosca per mostrare
     * un'immagine in chat invece di un link. Vive qui (non in
     * DeepChatApiController, che pure lo espone in risposta) perche' lo
     * usano anche la ricostruzione della history (DeepChatController) e
     * il payload del push asincrono (DeepChatGenerationWatcher).
     */
    public record FileRef(String src, String name, String type) {
    }

    /**
     * Una generazione puo' avere piu' di un'immagine (num_outputs > 1):
     * tutte finiscono nella stessa bolla di chat, deep-chat le mostra come
     * piu' file nello stesso turno.
     */
    public static List<FileRef> toFiles(Generation generation) {
        if (generation == null || generation.getImageFilenames().isEmpty()) {
            return null;
        }
        return generation.getImageFilenames().stream()
                .map(filename -> new FileRef("/images/" + filename, filename, generation.isVideo() ? "video" : "image"))
                .toList();
    }
}
