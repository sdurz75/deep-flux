package org.dual.replicate.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.dual.replicate.core.events.domain.CoreEventSource;
import org.dual.replicate.i18n.Messages;
import org.dual.replicate.replicate.ReplicateModelCatalog;
import org.dual.replicate.service.SystemEventService;
import org.dual.replicate.service.DeepChatFailedException;
import org.dual.replicate.service.DeepChatService;
import org.dual.replicate.service.GenerationParameterHandlers;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint JSON per il Web Component &lt;deep-chat&gt; (pagina servita da
 * DeepChatController, vedi templates/deep-chat.html). Deep Chat parla
 * JSON, non fragment HTML: e' l'eccezione prevista da CLAUDE.md per un
 * "componente complesso" montato come Web Component isolato su un div.
 * Il contratto richiesta/risposta qui sotto ricalca il default di
 * deep-chat: {"messages":[...], "model": "...", "parameters": {...}} in
 * ingresso ({@code parameters} arriva via requestInterceptor, vedi il
 * template — il pannello impostazioni li invia gia' col nome dei campi
 * del form-type corrente, risolti in "input" Replicate dallo stesso
 * GenerationParameterHandler usato dal form diretto, vedi
 * toGenerationParameters sotto), {"text": "...", "files":[{"src","name","type":"image"}]}
 * o {"error":"..."} in uscita — "files" con type "image" e' il formato
 * che deep-chat riconosce per mostrare un'immagine in chat, non solo
 * testo.
 */
@RestController
@RequestMapping("/api/deep-chat")
public class DeepChatApiController {

    private final DeepChatService deepChatService;
    private final ReplicateModelCatalog modelCatalog;
    private final GenerationParameterHandlers parameterHandlers;
    private final Messages messages;
    private final SystemEventService systemEvents;

    public DeepChatApiController(DeepChatService deepChatService,
                                  ReplicateModelCatalog modelCatalog,
                                  GenerationParameterHandlers parameterHandlers,
                                  Messages messages,
                                  SystemEventService systemEvents) {
        this.deepChatService = deepChatService;
        this.modelCatalog = modelCatalog;
        this.parameterHandlers = parameterHandlers;
        this.messages = messages;
        this.systemEvents = systemEvents;
    }

    @PostMapping
    public Reply chat(@RequestBody Request request) {
        try {
            // "files" e' sempre null qui: un'eventuale immagine non arriva
            // mai in questa risposta sincrona, il tool torna subito e il
            // risultato arriva in un secondo momento via push SSE (vedi
            // DeepChatGenerationWatcher/fragments/live-events.html).
            DeepChatService.Reply reply = deepChatService.reply(
                    request.conversationId(), request.messages(), request.model(), toGenerationParameters(request));
            return new Reply(reply.text(), null, null,
                    reply.startedGenerationIds().isEmpty() ? null : reply.startedGenerationIds());
        } catch (DeepChatFailedException e) {
            // Gia' registrato (tabella errori + toast) e scritto in cronologia da DeepChatService#reply.
            return new Reply(null, e.getMessage(), null, null);
        } catch (Exception e) {
            // Fallimento prima/fuori dalla chiamata LLM (conversazione inesistente, parametri non validi...).
            systemEvents.record(CoreEventSource.INTERNAL, "chatRequest", e, null, request.conversationId());
            return new Reply(null, messages.get("deepchat.error.contactAssistant", SystemEventService.sanitize(e)), null, null);
        }
    }

    /**
     * Delega al GenerationParameterHandler del form-type del modello
     * scelto (stesso condiviso con GenerationController, il form
     * diretto): se il modello non e' censito nel catalogo (non dovrebbe
     * succedere, la select lato client offre solo modelli censiti)
     * nessun parametro extra viene inviato, il modello riceve solo il
     * prompt. I valori arrivano dal client gia' come stringhe (vedi
     * deep-chat.html), stesso formato di un submit HTML.
     */
    private Map<String, Object> toGenerationParameters(Request request) {
        return modelCatalog.formTypeOf(request.model())
                .map(parameterHandlers::get)
                .map(handler -> handler.toParameterMap(toStringMap(request.parameters())))
                .orElseGet(Map::of);
    }

    private static Map<String, String> toStringMap(Map<String, Object> parameters) {
        Map<String, String> submitted = new LinkedHashMap<>();
        if (parameters != null) {
            parameters.forEach((key, value) -> {
                if (value != null) {
                    submitted.put(key, String.valueOf(value));
                }
            });
        }
        return submitted;
    }

    public record Request(
            Long conversationId,
            List<DeepChatService.Turn> messages,
            String model,
            Map<String, Object> parameters) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Reply(String text, String error, List<DeepChatService.FileRef> files, List<Long> generationIds) {
    }
}
