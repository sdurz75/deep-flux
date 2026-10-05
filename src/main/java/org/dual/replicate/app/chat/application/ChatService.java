package org.dual.replicate.app.chat.application;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.dual.replicate.app.chat.domain.AssistantException;
import org.dual.replicate.app.chat.domain.ChatAction;
import org.dual.replicate.app.chat.domain.ChatConversation;
import org.dual.replicate.app.chat.domain.ChatMessage;
import org.dual.replicate.app.chat.domain.ChatMessageRole;
import org.dual.replicate.app.chat.domain.ChatReply;
import org.dual.replicate.app.chat.domain.ChatTurn;
import org.dual.replicate.app.chat.domain.DeepChatFailedException;
import org.dual.replicate.app.chat.port.in.IChat;
import org.dual.replicate.app.chat.port.out.IAssistant;
import org.dual.replicate.app.chat.port.out.IChatConversationStore;
import org.dual.replicate.app.chat.port.out.IChatMessageStore;
import org.dual.replicate.app.generation.port.in.IGenerations;
import org.dual.replicate.app.shared.domain.AppEventSource;
import org.dual.replicate.app.shared.domain.AppEventSubjects;
import org.dual.replicate.core.events.domain.CoreEventSource;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.dual.replicate.core.kernel.EventSource;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

/**
 * Un turno di /deep-chat: persiste il turno utente, chiede la risposta all'assistente ({@link IAssistant}: LLM + tool, nell'adapter)
 * e persiste quella dell'assistente.
 *
 * /deep-chat supporta piu' conversazioni (ChatConversation), ognuna con la propria cronologia (ChatMessage, vedi CLAUDE.md punto 3
 * dello Scopo): {@link #reply} opera sempre su una conversazione precisa, passata per id dal client. Il client manda solo l'ultimo
 * messaggio (requestBodyLimits in templates/app/deep-chat.html): la cronologia che alimenta il modello la costruisce il server dal DB
 * ({@link ChatHistoryBuilder}: finestra limitata, esiti delle generazioni inclusi, errori esclusi), uguale dal vivo e dopo un reload. La gestione CRUD delle conversazioni
 * (creazione/rinomina/cancellazione) vive in ChatConversationService, non qui: reply() non e' @Transactional apposta, per non
 * tenere aperta una connessione DB per tutta la durata di una chiamata esterna lenta.
 */
@Service
public class ChatService implements IChat {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** Lunghezza massima del titolo auto-derivato dal primo turno utente di una conversazione, oltre la quale viene troncato. */
    private static final int TITLE_MAX_LENGTH = 60;

    private final IAssistant assistant;
    private final IChatConversationStore conversations;
    private final IChatMessageStore messages;
    private final IGenerations generations;
    private final ChatGenerationWatcher generationWatcher;
    private final ChatHistoryBuilder historyBuilder;
    private final Messages i18n;
    private final ISystemEvents systemEvents;

    public ChatService(IAssistant assistant,
                       IChatConversationStore conversations,
                       IChatMessageStore messages,
                       IGenerations generations,
                       ChatGenerationWatcher generationWatcher,
                       ChatHistoryBuilder historyBuilder,
                       Messages i18n,
                       ISystemEvents systemEvents) {
        this.assistant = assistant;
        this.conversations = conversations;
        this.messages = messages;
        this.generations = generations;
        this.generationWatcher = generationWatcher;
        this.historyBuilder = historyBuilder;
        this.i18n = i18n;
        this.systemEvents = systemEvents;
    }

    /**
     * {@code conversationId} identifica la conversazione a cui questo turno appartiene (scelta/gia' aperta lato UI): risolta subito,
     * prima della chiamata all'LLM, cosi' un id sconosciuto o non piu' valido non spreca una chiamata remota: l'eccezione risale al
     * chiamante (DeepChatApiController), che la traduce gia' genericamente in un messaggio d'errore in chat.
     *
     * {@code history} e' quella del client, di cui si usa solo l'ultimo turno utente (da persistere e a cui rispondere): quella che vede
     * l'assistente la costruisce {@link ChatHistoryBuilder} dal DB.
     *
     * {@code selectedModel} e' il modello Replicate scelto nel combobox lato UI, inviato dal client su ogni turno: per l'assistente e'
     * sia una nota di contesto sia il modello usato da generateImage (sempre e solo questo, non un parametro che l'LLM sceglie).
     * {@code generationParameters} sono i valori del pannello impostazioni (aspect_ratio, width, height, ...), gia' nel vocabolario
     * Replicate: NON vanno al modello LLM, sono impostazioni deterministiche scelte dall'utente.
     */
    @Override
    public ChatReply reply(Long conversationId, List<ChatTurn> history, String selectedModel, Map<String, Object> generationParameters) {
        ChatConversation conversation = conversations.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException(i18n.get("deepchat.error.conversationNotFound")));

        persistLatestUserTurn(conversation, history);

        // Catturata nel thread della richiesta (l'unico con una locale valorizzata da AcceptHeaderLocaleResolver): i watch
        // avviati piu' sotto girano su thread @Async, dove LocaleContextHolder e' vuoto e cadrebbe sulla locale della JVM.
        Locale locale = LocaleContextHolder.getLocale();
        List<Long> started = List.of();
        List<ChatAction> actions = List.of();
        try {
            String text;
            try {
                ChatReply answer = assistant.respond(conversation.getId(), modelHistory(conversation, history), selectedModel, generationParameters);
                started = answer.startedGenerationIds();
                actions = answer.actions();
                text = answer.text();
            } catch (RuntimeException e) {
                Throwable cause = e;
                if (e instanceof AssistantException failure) {
                    started = failure.startedGenerationIds();
                    cause = failure.getCause();
                }
                throw failTurn(conversation, AppEventSource.OPENROUTER, "chatTurn", cause);
            }

            try {
                messages.save(new ChatMessage(conversation, ChatMessageRole.AI, text, null));
            } catch (RuntimeException e) {
                throw failTurn(conversation, CoreEventSource.INTERNAL, "saveChatTurn", e);
            }
            return new ChatReply(text, started, actions);
        } finally {
            // Nel finally PIU' ESTERNO, dopo aver salvato il turno AI (non solo sul percorso di successo: una generazione gia'
            // avviata dal tool deve arrivare comunque via push anche se la chiamata all'LLM fallisce dopo) cosi' l'ordine
            // cronologico/di persistenza resta sempre corretto: un modello veloce (es. flux-schnell) puo' finire prima che l'LLM
            // produca il testo del turno, il messaggio "immagine pronta" non deve mai precedere quello del turno che l'ha avviata.
            for (Long id : started) {
                // Per id: un fallimento su uno non deve impedire agli altri di avere il watcher, ne' mascherare l'eccezione
                // originale del turno (il recupero riprende comunque le orfane).
                try {
                    generations.attachToConversation(id, conversation.getId());
                    generationWatcher.watch(id, conversation.getId(), locale);
                } catch (RuntimeException e) {
                    systemEvents.record(CoreEventSource.INTERNAL, "watchStart", e, AppEventSubjects.of(id, conversation.getId()));
                }
            }
        }
    }

    /**
     * Il turno e' fallito: registra l'errore (log + tabella + toast), scrive in cronologia un turno ASSISTANT d'errore (il turno USER
     * e' gia' salvato: senza, resterebbe orfano) e ritorna l'eccezione da lanciare, gia' col messaggio per l'utente. Non lancia mai
     * da se'.
     */
    private DeepChatFailedException failTurn(ChatConversation conversation, EventSource source, String operation, Throwable cause) {
        systemEvents.record(source, operation, cause, AppEventSubjects.of(null, conversation.getId()));
        String userMessage = i18n.get("deepchat.error.contactAssistant", ISystemEvents.sanitize(cause));
        try {
            messages.save(ChatMessage.errorTurn(conversation, userMessage));
        } catch (RuntimeException e) {
            log.error("Impossibile salvare il turno d'errore in cronologia: {}", e.toString());
        }
        return new DeepChatFailedException(userMessage, cause);
    }

    /**
     * Persiste solo l'ultimo turno della history mandata dal client (il
     * messaggio utente che ha innescato questa chiamata, l'unico che il
     * client manda): i turni precedenti sono gia' su DB dalle chiamate passate.
     *
     * Se la conversazione non ha ancora un titolo, lo deriva (troncato)
     * da questo stesso turno — mai un letterale di default persistito,
     * vedi ChatConversation — e aggiorna updatedAt (touch): e' questo il
     * punto che riordina la sidebar per recenza, PRIMA della chiamata
     * all'LLM sotto, cosi' l'ordine si aggiorna anche se quella chiamata
     * fallisce.
     */
    private void persistLatestUserTurn(ChatConversation conversation, List<ChatTurn> history) {
        if (history.isEmpty()) {
            return;
        }
        ChatTurn latest = history.get(history.size() - 1);
        if (!"user".equals(latest.role())) {
            return;
        }
        if (conversation.getTitle() == null) {
            conversation.setTitle(truncateTitle(latest.text()));
        }
        conversation.touch();
        conversations.save(conversation);
        messages.save(new ChatMessage(conversation, ChatMessageRole.USER, latest.text(), null));
    }

    /** La cronologia per il modello: quella del server, non quella del client (che manda solo l'ultimo messaggio). */
    private List<ChatTurn> modelHistory(ChatConversation conversation, List<ChatTurn> clientHistory) {
        ChatTurn latest = clientHistory.isEmpty() ? null : clientHistory.get(clientHistory.size() - 1);
        return historyBuilder.build(conversation.getId(), latest != null && ChatTurn.USER.equals(latest.role()) ? latest : null);
    }

    private static String truncateTitle(String text) {
        String trimmed = text.strip();
        return trimmed.length() <= TITLE_MAX_LENGTH ? trimmed : trimmed.substring(0, TITLE_MAX_LENGTH) + "…";
    }
}
