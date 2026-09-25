package org.dual.replicate.controller;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.dual.replicate.i18n.Messages;
import org.dual.replicate.service.DeepChatService;
import org.dual.replicate.service.GenerationParameters;
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
 * deep-chat: {"messages":[...], "model": "...", "aspect_ratio": "...",
 * ...} in ingresso (questi campi extra arrivano via requestInterceptor,
 * vedi il template — il pannello impostazioni li invia gia' nel
 * vocabolario Replicate, snake_case, per evitare rinominazioni lungo
 * la catena), {"text": "...", "files":[{"src","name","type":"image"}]}
 * o {"error":"..."} in uscita — "files" con type "image" e' il formato
 * che deep-chat riconosce per mostrare un'immagine in chat, non solo
 * testo.
 */
@RestController
@RequestMapping("/api/deep-chat")
public class DeepChatApiController {

    private final DeepChatService deepChatService;
    private final Messages messages;

    public DeepChatApiController(DeepChatService deepChatService, Messages messages) {
        this.deepChatService = deepChatService;
        this.messages = messages;
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
            return new Reply(reply.text(), null, null);
        } catch (Exception e) {
            return new Reply(null, messages.get("deepchat.error.contactAssistant", e.getMessage()), null);
        }
    }

    /**
     * Delega a GenerationParameters.toMap (condiviso con GenerationController,
     * il form diretto): solo i campi effettivamente presenti finiscono
     * nella mappa, cosi' un modello che non supporta uno di questi
     * parametri non lo riceve proprio, invece di fallire su un valore
     * imposto ma inutile per lui.
     */
    private Map<String, Object> toGenerationParameters(Request request) {
        return GenerationParameters.toMap(request.aspectRatio(), request.width(), request.height(),
                request.outputFormat(), request.numInferenceSteps(), request.guidanceScale(),
                request.seed(), request.loraScale(), request.fluxModel(), request.numOutputs());
    }

    public record Request(
            Long conversationId,
            List<DeepChatService.Turn> messages,
            String model,
            @JsonProperty("aspect_ratio") String aspectRatio,
            Integer width,
            Integer height,
            @JsonProperty("output_format") String outputFormat,
            @JsonProperty("num_inference_steps") Integer numInferenceSteps,
            @JsonProperty("guidance_scale") Double guidanceScale,
            Long seed,
            @JsonProperty("lora_scale") Double loraScale,
            @JsonProperty("flux_model") String fluxModel,
            @JsonProperty("num_outputs") Integer numOutputs) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Reply(String text, String error, List<DeepChatService.FileRef> files) {
    }
}
