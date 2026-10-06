package org.dual.replicate.app.chat.adapter.in.web;

import org.dual.replicate.app.generation.port.in.IGenerations;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Galleria contestuale della conversazione (accordion in deep-chat.html), solo htmx: nessuna vista a pagina intera, non avrebbe senso come
 * pagina a se stante. Richiamata ad ogni evento "new-message" del Web Component (vedi deep-chat.html), cosi' una nuova immagine generata in
 * chat appare qui senza dover ricaricare l'intera pagina. E' dell'app (conosce le generazioni), non del motore della chat.
 */
@Controller
class ChatGalleryController {

    private final IGenerations generations;
    private final Messages messages;

    ChatGalleryController(IGenerations generations, Messages messages) {
        this.generations = generations;
        this.messages = messages;
    }

    @GetMapping("/deep-chat/{id}/gallery")
    String gallery(@PathVariable Long id, Model model) {
        model.addAttribute("contextualItems", generations.succeededItemsForConversation(id));
        model.addAttribute("contextualGalleryEmptyMessage", messages.get("deepChat.accordion.gallery.empty"));
        model.addAttribute("conversationId", id);
        return "fragments/app/gallery :: gridOrEmpty(items=${contextualItems}, emptyMessage=${contextualGalleryEmptyMessage}, conversationId=${conversationId})";
    }
}
