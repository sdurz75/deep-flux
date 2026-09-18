package org.dual.replicate.controller;

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

    public DeepChatController(ReplicateModelCatalog modelCatalog) {
        this.modelCatalog = modelCatalog;
    }

    @GetMapping("/deep-chat")
    public String page(Model model) {
        model.addAttribute("models", modelCatalog.models());
        return "deep-chat";
    }
}
