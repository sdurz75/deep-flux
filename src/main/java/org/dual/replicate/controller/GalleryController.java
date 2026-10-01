package org.dual.replicate.controller;

import java.util.ArrayList;
import java.util.List;

import org.dual.replicate.core.web.PaginationSupport;
import org.dual.replicate.domain.Generation;
import org.dual.replicate.domain.GenerationStatus;
import org.dual.replicate.repository.GenerationRepository;
import org.dual.replicate.service.GalleryItem;
import org.dual.replicate.service.GenerationService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Galleria delle immagini generate: SOLO generazioni SUCCEEDED,
 * paginazione classica (numeri di pagina + precedente/successiva, markup
 * in fragments/core/pagination.html, riusabile da futuri altri listati) e
 * cancellazione in blocco dalla griglia stessa (checkbox per card, vedi
 * fragments/app/gallery.html/gallery-card.html). Il dettaglio di una singola
 * generazione (prompt/parametri, cancellazione singola e per-immagine)
 * vive invece su GET /generations/{id} (vedi GenerationController e
 * CLAUDE.md): le card di questa griglia ci linkano, non c'e' piu' una
 * pagina di dettaglio separata qui.
 */
@Controller
@RequestMapping("/gallery")
public class GalleryController {

    private static final int PAGE_SIZE = 12;
    private static final String TAB_ALL = "all";
    private static final String TAB_FAVOURITES = "favourites";

    private final GenerationRepository repository;
    private final GenerationService generationService;

    public GalleryController(GenerationRepository repository, GenerationService generationService) {
        this.repository = repository;
        this.generationService = generationService;
    }

    @GetMapping
    public String list(@RequestParam(defaultValue = "1") int page,
                        @RequestParam(defaultValue = TAB_ALL) String tab,
                        @RequestHeader(value = "HX-Request", required = false) String hxRequest,
                        Model model) {
        int pageIndex = Math.max(0, page - 1);
        boolean favourites = TAB_FAVOURITES.equals(tab);
        Page<GalleryItem> result = fetch(favourites, pageIndex);

        // Una pagina che esisteva puo' smettere di esistere fra un refresh e
        // l'altro (cancellazione in blocco dell'ultima pagina, vedi
        // GalleryController#deleteSelected): il refresh SSE ri-richiede
        // esattamente currentPage (fragments/app/gallery.html, hx-get="@{/gallery(page=...)}"),
        // che a questo punto sarebbe oltre l'ultima pagina rimasta - senza
        // questo aggiustamento l'utente vedrebbe "nessuna immagine" anche se
        // le pagine precedenti hanno ancora contenuto.
        if (result.isEmpty() && result.getTotalPages() > 0 && pageIndex >= result.getTotalPages()) {
            pageIndex = result.getTotalPages() - 1;
            result = fetch(favourites, pageIndex);
        }
        int currentPage = pageIndex + 1;

        model.addAttribute("items", result.getContent());
        model.addAttribute("tab", favourites ? TAB_FAVOURITES : TAB_ALL);
        model.addAttribute("currentPage", currentPage);
        model.addAttribute("totalPages", result.getTotalPages());
        model.addAttribute("hasPrevious", result.hasPrevious());
        model.addAttribute("hasNext", result.hasNext());
        model.addAttribute("pageNumbers", PaginationSupport.window(currentPage, result.getTotalPages()));

        boolean isHtmxRequest = "true".equalsIgnoreCase(hxRequest);
        // Nota: come vista di risposta diretta (non dentro un th:replace inline)
        // Thymeleaf richiede parametri nominati, non posizionali.
        return isHtmxRequest
                ? "fragments/app/gallery :: content(items=${items}, tab=${tab}, currentPage=${currentPage}, "
                        + "totalPages=${totalPages}, hasPrevious=${hasPrevious}, hasNext=${hasNext}, pageNumbers=${pageNumbers})"
                : "app/gallery";
    }

    /** Tab "Tutte": una card per generazione (primo file); tab "Preferiti": una card per file con la star. */
    private Page<GalleryItem> fetch(boolean favourites, int pageIndex) {
        PageRequest pageable = PageRequest.of(pageIndex, PAGE_SIZE);
        return favourites
                ? repository.findFavouriteItems(pageable)
                : repository.findByStatusOrderByCreatedAtDesc(GenerationStatus.SUCCEEDED, pageable).map(GalleryItem::first);
    }


    /**
     * Cancellazione in blocco dalla griglia (checkbox multiple, bottone
     * "Elimina selezionate" in fragments/app/gallery.html :: grid, disabilitato
     * lato client finche' la selezione e' vuota — vedi fragments/core/button.html
     * :: dangerSelectable). La pagina corrente (globale o contestuale di
     * /deep-chat) resta valida dopo la cancellazione: nessun redirect,
     * risposta vuota. Il refresh — sia della tab che ha cliccato sia di
     * qualunque altra tab con una galleria aperta — arriva dall'evento SSE
     * pubblicato da GenerationService#deleteAll (GenerationsDeletedEvent →
     * "gallery-update", vedi GenerationEventBroadcaster), non da uno swap
     * diretto di questa risposta.
     * <p>
     * {@code required = false} sul parametro e' puramente difensivo: il
     * bottone che chiama questo endpoint e' disabilitato lato client
     * quando non c'e' selezione, quindi in pratica non dovrebbe mai
     * arrivare senza id.
     * <p>
     * {@code @ResponseBody}: senza, un metodo void su un {@code @Controller}
     * verrebbe risolto da Spring con RequestToViewNameTranslator, che
     * proverebbe a renderizzare un template "gallery/delete-selected"
     * inesistente (verificato dal vivo, 500 in test).
     */
    @PostMapping("/delete-selected")
    @ResponseBody
    public void deleteSelected(@RequestParam(required = false) List<Long> ids) {
        if (ids != null && !ids.isEmpty()) {
            generationService.deleteAll(ids);
        }
    }
}
