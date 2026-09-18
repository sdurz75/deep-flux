package org.dual.replicate.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Pagina che ospita il Web Component &lt;deep-chat&gt; (vedi
 * DeepChatApiController per l'endpoint JSON che lo alimenta).
 */
@Controller
public class DeepChatController {

    @GetMapping("/deep-chat")
    public String page() {
        return "deep-chat";
    }
}
