package org.hexa.core.chat.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.hexa.core.chat.domain.AssistantException;
import org.hexa.core.chat.domain.ChatConversation;
import org.hexa.core.chat.domain.ChatMessage;
import org.hexa.core.chat.domain.ChatMessageRole;
import org.hexa.core.chat.domain.ChatReply;
import org.hexa.core.chat.domain.ChatTurn;
import org.hexa.core.chat.domain.DeepChatFailedException;
import org.hexa.core.chat.port.in.IChat;
import org.hexa.core.chat.port.out.IAssistant;
import org.hexa.core.chat.port.out.IChatConversationStore;
import org.hexa.core.chat.port.out.IChatMessageStore;
import org.hexa.core.chat.domain.event.ChatOutcomesStartedEvent;
import org.hexa.core.chat.port.in.IChatTurnContributor;
import org.hexa.core.chat.domain.ChatEventSubjects;
import org.hexa.core.events.domain.CoreEventSource;
import org.hexa.core.events.port.in.ISystemEvents;
import org.hexa.core.kernel.EventSource;
import org.hexa.core.kernel.i18n.Messages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
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
    private final List<IChatTurnContributor> contributors;
    private final ApplicationEventPublisher events;
    private final ChatHistoryBuilder historyBuilder;
    private final Messages i18n;
    private final ISystemEvents systemEvents;

    public ChatService(IAssistant assistant,
                       IChatConversationStore conversations,
                       IChatMessageStore messages,
                       List<IChatTurnContributor> contributors,
                       ApplicationEventPublisher events,
                       ChatHistoryBuilder historyBuilder,
                       Messages i18n,
                       ISystemEvents systemEvents) {
        this.assistant = assistant;
        this.conversations = conversations;
        this.messages = messages;
        this.contributors = contributors;
        this.events = events;
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
     * {@code clientSettings} sono le impostazioni opache inviate dal client su ogni turno: i {@code IChatTurnContributor} le trasformano
     * nel contesto del turno (per l'app: il modello scelto nel combobox, usato da generateImage e mai deciso dall'LLM, e i parametri del
     * pannello, gia' nel vocabolario Replicate). NON vanno al modello LLM: sono impostazioni deterministiche scelte dall'utente.
     */
    @Override
    public ChatReply reply(Long conversationId, List<ChatTurn> history, Map<String, Object> clientSettings) {
        ChatConversation conversation = conversations.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException(i18n.get("deepchat.error.conversationNotFound")));
        // Prima di persistere alcunche': un contesto non valido (parametri sbagliati) fa fallire il turno senza lasciare un turno utente orfano.
        Map<String, Object> turnContext = new LinkedHashMap<>();
        contributors.forEach(contributor -> turnContext.putAll(contributor.contribute(clientSettings == null ? Map.of() : clientSettings)));

        persistLatestUserTurn(conversation, history);

        // Catturata nel thread della richiesta (l'unico con una locale valorizzata da AcceptHeaderLocaleResolver): i watch
        // avviati piu' sotto girano su thread @Async, dove LocaleContextHolder e' vuoto e cadrebbe sulla locale della JVM.
        Locale locale = LocaleContextHolder.getLocale();
        List<Long> started = List.of();
        Map<String, Object> extras = Map.of();
        try {
            String text;
            try {
                ChatReply answer = assistant.respond(conversation.getId(), modelHistory(conversation, history), turnContext);
                started = answer.startedOutcomeRefs();
                extras = answer.extras();
                text = answer.text();
            } catch (RuntimeException e) {
                Throwable cause = e;
                if (e instanceof AssistantException failure) {
                    started = failure.startedOutcomeRefs();
                    cause = failure.getCause();
                }
                throw failTurn(conversation, CoreEventSource.OPENROUTER, "chatTurn", cause);
            }

            try {
                messages.save(new ChatMessage(conversation, ChatMessageRole.AI, text, null));
            } catch (RuntimeException e) {
                throw failTurn(conversation, CoreEventSource.INTERNAL, "saveChatTurn", e);
            }
            return new ChatReply(text, started, extras);
        } finally {
            // Nel finally PIU' ESTERNO, dopo aver salvato il turno AI (non solo sul percorso di successo: una generazione gia'
            // avviata dal tool deve arrivare comunque via push anche se la chiamata all'LLM fallisce dopo) cosi' l'ordine
            // cronologico/di persistenza resta sempre corretto: un modello veloce (es. flux-schnell) puo' finire prima che l'LLM
            // produca il testo del turno, il messaggio "immagine pronta" non deve mai precedere quello del turno che l'ha avviata.
            if (!started.isEmpty()) {
                // Chi possiede il dominio degli esiti li aggancia alla conversazione e ne attende l'esito (il suo listener non deve
                // poter mascherare l'eccezione originale del turno: il recupero riprende comunque le orfane).
                try {
                    events.publishEvent(new ChatOutcomesStartedEvent(conversation.getId(), started, locale));
                } catch (RuntimeException e) {
                    systemEvents.record(CoreEventSource.INTERNAL, "watchStart", e, ChatEventSubjects.ofConversation(conversation.getId()));
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
        systemEvents.record(source, operation, cause, ChatEventSubjects.ofConversation(conversation.getId()));
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
