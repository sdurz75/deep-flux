package org.dual.replicate.controller;

import tools.jackson.databind.ObjectMapper;
import org.dual.replicate.replicate.ReplicateModelCatalog;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Pagina che ospita il Web Component &lt;deep-chat&gt; (vedi
 * DeepChatApiController per l'endpoint JSON che lo alimenta).
 */
@Controller
public class DeepChatController {

    private final ReplicateModelCatalog modelCatalog;
    private final ObjectMapper objectMapper;

    public DeepChatController(ReplicateModelCatalog modelCatalog, ObjectMapper objectMapper) {
        this.modelCatalog = modelCatalog;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/deep-chat")
    public String page(Model model) {
        // Serializzate qui (non nel template) come stringhe JSON gia'
        // pronte: il template le inlinea via Thymeleaf JS-inlining
        // (th:inline="javascript"), che sa escapare una String Java per
        // un contesto JS in modo sicuro senza dover capire come
        // Thymeleaf serializzerebbe i record (owner()/name() non sono
        // getter "getOwner()" in stile JavaBean).
        model.addAttribute("personalModelsJson", objectMapper.writeValueAsString(modelCatalog.personalModels()));
        model.addAttribute("catalogModelsJson", objectMapper.writeValueAsString(modelCatalog.models()));
        return "deep-chat";
    }
}
