package org.dual.replicate.controller;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.dual.replicate.domain.Generation;
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
 * Il contratto richiesta/risposta qui sotto ricalca il default di
 * deep-chat: {"messages":[...], "model": "..."} in ingresso (il campo
 * "model" arriva via additionalBodyProps, vedi il template), {"text":
 * "...", "files":[{"src","name","type":"image"}]} o {"error":"..."} in
 * uscita — "files" con type "image" e' il formato che deep-chat
 * riconosce per mostrare un'immagine in chat, non solo testo.
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
            DeepChatService.Reply reply = deepChatService.reply(request.messages(), request.model());
            List<FileRef> files = toFiles(reply.image());
            return new Reply(reply.text(), null, files);
        } catch (Exception e) {
            return new Reply(null, "Errore nel contattare l'assistente: " + e.getMessage(), null);
        }
    }

    private List<FileRef> toFiles(Generation image) {
        if (image == null || image.getImageFilename() == null) {
            return null;
        }
        return List.of(new FileRef("/images/" + image.getImageFilename(), image.getImageFilename(), "image"));
    }

    public record Request(List<DeepChatService.Turn> messages, String model) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Reply(String text, String error, List<FileRef> files) {
    }

    public record FileRef(String src, String name, String type) {
    }
}
