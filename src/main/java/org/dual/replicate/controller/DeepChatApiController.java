package org.dual.replicate.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.dual.replicate.domain.Generation;
import org.dual.replicate.service.DeepChatService;
import org.springframework.http.ResponseEntity;
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

    public DeepChatApiController(DeepChatService deepChatService) {
        this.deepChatService = deepChatService;
    }

    @PostMapping
    public Reply chat(@RequestBody Request request) {
        try {
            DeepChatService.Reply reply = deepChatService.reply(
                    request.messages(), request.model(), toGenerationParameters(request));
            List<FileRef> files = toFiles(reply.image());
            return new Reply(reply.text(), null, files);
        } catch (Exception e) {
            return new Reply(null, "Errore nel contattare l'assistente: " + e.getMessage(), null);
        }
    }

    /**
     * Azzera la cronologia persistita (vedi DeepChatService.resetHistory)
     * e forza un reload completo della pagina via l'header di risposta
     * "HX-Refresh" che htmx riconosce nativamente: piu' semplice che
     * conoscere l'API JS di deep-chat per svuotare i messaggi gia' in
     * pagina, e comunque servirebbe un reload per ripopolare
     * initialMessages da capo (vedi DeepChatController).
     */
    @PostMapping("/reset")
    public ResponseEntity<Void> reset() {
        deepChatService.resetHistory();
        return ResponseEntity.ok().header("HX-Refresh", "true").build();
    }

    /**
     * Solo i campi effettivamente presenti (il pannello lato UI non
     * invia quelli lasciati vuoti/di default, vedi requestInterceptor
     * nel template): cosi' un modello che non supporta uno di questi
     * parametri non lo riceve proprio, invece di fallire su un valore
     * imposto ma inutile per lui.
     */
    private Map<String, Object> toGenerationParameters(Request request) {
        Map<String, Object> params = new LinkedHashMap<>();
        putIfPresent(params, "aspect_ratio", request.aspectRatio());
        putIfPresent(params, "width", request.width());
        putIfPresent(params, "height", request.height());
        putIfPresent(params, "output_format", request.outputFormat());
        putIfPresent(params, "num_inference_steps", request.numInferenceSteps());
        putIfPresent(params, "guidance_scale", request.guidanceScale());
        putIfPresent(params, "seed", request.seed());
        putIfPresent(params, "lora_scale", request.loraScale());
        return params;
    }

    private void putIfPresent(Map<String, Object> params, String key, Object value) {
        if (value != null) {
            params.put(key, value);
        }
    }

    /** Package-private: riusata da DeepChatController per ricostruire l'allegato immagine al ripristino della cronologia. */
    static List<FileRef> toFiles(Generation image) {
        if (image == null || image.getImageFilename() == null) {
            return null;
        }
        return List.of(new FileRef("/images/" + image.getImageFilename(), image.getImageFilename(), "image"));
    }

    public record Request(
            List<DeepChatService.Turn> messages,
            String model,
            @JsonProperty("aspect_ratio") String aspectRatio,
            Integer width,
            Integer height,
            @JsonProperty("output_format") String outputFormat,
            @JsonProperty("num_inference_steps") Integer numInferenceSteps,
            @JsonProperty("guidance_scale") Double guidanceScale,
            Long seed,
            @JsonProperty("lora_scale") Double loraScale) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Reply(String text, String error, List<FileRef> files) {
    }

    public record FileRef(String src, String name, String type) {
    }
}
