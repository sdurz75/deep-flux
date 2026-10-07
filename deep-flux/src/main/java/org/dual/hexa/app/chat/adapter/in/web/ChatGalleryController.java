package org.dual.hexa.app.chat.adapter.in.web;

import org.dual.hexa.app.generation.domain.GalleryItem;
import org.dual.hexa.app.generation.port.in.IGenerations;
import org.dual.hexa.core.kernel.Paged;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Galleria contestuale della conversazione (popover dello slot `settings` di chat-host.html), a scroll infinito, solo htmx: nessuna vista a pagina intera, non avrebbe senso come
 * pagina a se stante. Richiamata ad ogni evento "new-message" del Web Component (vedi deep-chat.html), cosi' una nuova immagine generata in
 * chat appare qui senza dover ricaricare l'intera pagina. E' dell'app (conosce le generazioni), non del motore della chat.
 */
@Controller
class ChatGalleryController {

    /** Generazioni per pagina (come la galleria globale). */
    static final int PAGE_SIZE = 12;

    private final IGenerations generations;
    private final Messages messages;

    ChatGalleryController(IGenerations generations, Messages messages) {
        this.generations = generations;
        this.messages = messages;
    }

    @GetMapping("/deep-chat/{id}/gallery")
    String gallery(@PathVariable Long id,
                   @RequestParam(defaultValue = "1") int page,
                   @RequestParam(defaultValue = "false") boolean more,
                   @RequestHeader(value = "HX-Request", required = false) String hxRequest,
                   Model model) {
        boolean append = more && "true".equalsIgnoreCase(hxRequest);
        Paged<GalleryItem> result = generations.succeededItemsForConversationPage(id, append ? Math.max(0, page - 1) : 0, PAGE_SIZE);
        model.addAttribute("contextualItems", result.content());
        model.addAttribute("contextualNextPage", result.hasNext() ? result.pageIndex() + 2 : null);
        model.addAttribute("contextualGalleryEmptyMessage", messages.get("deepChat.accordion.gallery.empty"));
        model.addAttribute("conversationId", id);
        if (append) {
            return "fragments/app/gallery :: cards(items=${contextualItems}, selectable=true, selectionByFile=true, "
                    + "conversationId=${conversationId}, nextPage=${contextualNextPage}, tab=null, tag=null)";
        }
        return "fragments/app/gallery :: gridOrEmpty(items=${contextualItems}, emptyMessage=${contextualGalleryEmptyMessage}, "
                + "conversationId=${conversationId}, nextPage=${contextualNextPage})";
    }
}
