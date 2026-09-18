package org.dual.replicate.controller;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.dual.replicate.service.DeepChatService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint JSON per il Web Component &lt;deep-chat&gt; (pagina servita da
 * DeepChatController, vedi templates/deep-chat.html). Deep Chat parla
 * JSON, non fragment HTML: e' l'eccezione prevista da CLAUDE.md per un
 * "componente complesso" montato come Web Component isolato su un div.
 * Il contratto richiesta/risposta qui sotto ricalca esattamente il
 * default di deep-chat (nessun requestInterceptor/responseInterceptor
 * lato client): {"messages":[{"role":"user"|"ai","text":"..."}, ...]}
 * in ingresso, {"text":"..."} o {"error":"..."} in uscita.
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
            String text = deepChatService.reply(request.messages());
            return new Reply(text, null);
        } catch (Exception e) {
            return new Reply(null, "Errore nel contattare l'assistente: " + e.getMessage());
        }
    }

    public record Request(List<DeepChatService.Turn> messages) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Reply(String text, String error) {
    }
}
