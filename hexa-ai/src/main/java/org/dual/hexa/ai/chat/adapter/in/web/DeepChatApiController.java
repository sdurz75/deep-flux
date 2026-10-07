package org.dual.hexa.ai.chat.adapter.in.web;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.dual.hexa.ai.chat.domain.ChatEventSubjects;
import org.dual.hexa.core.events.domain.CoreEventSource;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.dual.hexa.core.events.port.in.ISystemEvents;
import org.dual.hexa.ai.chat.domain.ChatReply;
import org.dual.hexa.ai.chat.domain.ChatTurn;
import org.dual.hexa.ai.chat.port.in.IChat;
import org.dual.hexa.ai.chat.domain.DeepChatFailedException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint JSON per il Web Component &lt;deep-chat&gt; (pagina servita da
 * DeepChatController, vedi templates/app/deep-chat.html). Deep Chat parla
 * JSON, non fragment HTML: e' l'eccezione prevista da CLAUDE.md per un
 * "componente complesso" montato come Web Component isolato su un div.
 * Il contratto richiesta/risposta qui sotto ricalca il default di
 * deep-chat: {"messages":[...], "conversationId": n, "settings": {...}} in
 * ingresso ({@code settings} e' opaco per la chat: lo compone il requestInterceptor
 * del template e lo interpretano i {@code IChatTurnContributor}, per l'app modello e parametri
 * del pannello impostazioni), {"text": "..."} piu' le parti extra dei toolkit
 * (chiavi di {@code ChatReply#extras}) o {"error":"..."} in uscita.
 */
@RestController
@RequestMapping("/api/deep-chat")
public class DeepChatApiController {

    private final IChat chat;
    private final Messages messages;
    private final ISystemEvents systemEvents;

    public DeepChatApiController(IChat chat, Messages messages, ISystemEvents systemEvents) {
        this.chat = chat;
        this.messages = messages;
        this.systemEvents = systemEvents;
    }

    @PostMapping
    public Reply chat(@RequestBody Request request) {
        try {
            // Nessuna immagine in questa risposta sincrona: il tool torna subito e il risultato arriva in un secondo momento via push SSE
            // (vedi ChatGenerationWatcher/fragments/live-events.html); i toolkit aggiungono le loro parti in "extras".
            ChatReply reply = chat.reply(request.conversationId(), request.messages(), request.settings());
            return new Reply(reply.text(), null, reply.extras().isEmpty() ? null : reply.extras());
        } catch (DeepChatFailedException e) {
            // Gia' registrato (tabella errori + toast) e scritto in cronologia da ChatService#reply.
            return new Reply(null, e.getMessage(), null);
        } catch (Exception e) {
            // Fallimento prima/fuori dalla chiamata LLM (conversazione inesistente, parametri non validi...).
            systemEvents.record(CoreEventSource.INTERNAL, "chatRequest", e, ChatEventSubjects.ofConversation(request.conversationId()));
            return new Reply(null, messages.get("deepchat.error.contactAssistant", ISystemEvents.sanitize(e)), null);
        }
    }

    public record Request(
            Long conversationId,
            List<ChatTurn> messages,
            Map<String, Object> settings) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Reply(String text, String error, @JsonAnyGetter Map<String, Object> extras) {
    }
}
