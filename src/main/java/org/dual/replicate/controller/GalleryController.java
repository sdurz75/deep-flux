package org.dual.replicate.controller;

import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.dual.replicate.domain.Generation;
import org.dual.replicate.domain.GenerationStatus;
import org.dual.replicate.i18n.Messages;
import org.dual.replicate.repository.GenerationRepository;
import org.dual.replicate.service.GenerationService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * Galleria delle immagini generate: paginazione classica (numeri di
 * pagina + precedente/successiva, markup in fragments/pagination.html,
 * riusabile da futuri altri listati), pagina di dettaglio dedicata per
 * consultare prompt/parametri di una singola generazione ed eliminarla
 * (unico punto dove l'eliminazione e' disponibile: la card della
 * griglia, in fragments/gallery-card.html, non la espone piu').
 */
@Controller
@RequestMapping("/gallery")
public class GalleryController {

    private static final int PAGE_SIZE = 12;

    private final GenerationRepository repository;
    private final GenerationService generationService;
    private final Messages messages;

    public GalleryController(GenerationRepository repository, GenerationService generationService, Messages messages) {
        this.repository = repository;
        this.generationService = generationService;
        this.messages = messages;
    }

    @GetMapping
    public String list(@RequestParam(defaultValue = "1") int page,
                        @RequestHeader(value = "HX-Request", required = false) String hxRequest,
                        Model model) {
        int pageIndex = Math.max(0, page - 1);
        var pageRequest = PageRequest.of(pageIndex, PAGE_SIZE);
        Page<Generation> result = repository.findByStatusOrderByCreatedAtDesc(GenerationStatus.SUCCEEDED, pageRequest);
        int currentPage = pageIndex + 1;

        model.addAttribute("generations", result.getContent());
        model.addAttribute("currentPage", currentPage);
        model.addAttribute("totalPages", result.getTotalPages());
        model.addAttribute("hasPrevious", result.hasPrevious());
        model.addAttribute("hasNext", result.hasNext());
        model.addAttribute("pageNumbers", paginationWindow(currentPage, result.getTotalPages()));

        boolean isHtmxRequest = "true".equalsIgnoreCase(hxRequest);
        // Nota: come vista di risposta diretta (non dentro un th:replace inline)
        // Thymeleaf richiede parametri nominati, non posizionali.
        return isHtmxRequest
                ? "fragments/gallery :: content(generations=${generations}, currentPage=${currentPage}, "
                        + "totalPages=${totalPages}, hasPrevious=${hasPrevious}, hasNext=${hasNext}, pageNumbers=${pageNumbers})"
                : "gallery";
    }

    /**
     * Numeri di pagina da mostrare (1-indexed): sempre prima e ultima
     * pagina, una finestra di una pagina prima/dopo quella corrente, con
     * {@code null} come segnaposto di ellissi per i buchi in mezzo — cosi'
     * la paginazione resta leggibile anche quando la galleria cresce molto
     * invece di elencare centinaia di numeri.
     */
    private static List<Integer> paginationWindow(int currentPage, int totalPages) {
        if (totalPages <= 1) {
            return List.of();
        }

        List<Integer> pages = new ArrayList<>();
        pages.add(1);

        int windowStart = Math.max(2, currentPage - 1);
        int windowEnd = Math.min(totalPages - 1, currentPage + 1);

        if (windowStart > 2) {
            pages.add(null);
        }
        for (int p = windowStart; p <= windowEnd; p++) {
            pages.add(p);
        }
        if (windowEnd < totalPages - 1) {
            pages.add(null);
        }
        pages.add(totalPages);

        return pages;
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Generation generation = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, messages.get("gallery.error.notFound")));
        model.addAttribute("generation", generation);
        return "gallery-detail";
    }

    /**
     * Elimina una generazione (riga + file immagine). Niente verbo DELETE:
     * un form HTML non puo' inviarlo senza JavaScript, quindi si usa POST
     * come per la creazione (vedi GenerationController). Chiamato solo dal
     * form nella pagina di dettaglio (gallery-detail.html): dopo la
     * cancellazione l'id non esiste piu', quindi il browser deve SEMPRE
     * lasciare quella pagina — niente piu' "stessa URL, due risposte" con
     * un fragment vuoto per rimuovere solo la card in place (non c'e' piu'
     * nessuna card da cui questo form possa fare hx-target="closest"),
     * entrambi i rami sono un redirect a /gallery.
     * <p>
     * Per htmx si usa l'header di risposta "HX-Redirect", non "HX-Refresh"
     * (che ricaricherebbe la SOLA pagina corrente): qui invece serve
     * navigare altrove, la pagina corrente non esiste piu'. L'URL rispetta
     * un eventuale prefisso di
     * reverse proxy esattamente come farebbe {@code @{...}} in un
     * template: {@link HttpServletRequest#getContextPath()} e' gia' stato
     * riscritto a runtime da {@code ForwardedHeaderFilter} per includere
     * {@code X-Forwarded-Prefix} (vedi {@code server.forward-headers-strategy}
     * in application.yml) — primo caso in questo codebase di URL costruito
     * lato Java che deve rispettare quel subpath, vedi CLAUDE.md.
     * <p>
     * Iniettare {@link HttpServletResponse} e ritornare {@code null} e' la
     * convenzione standard Spring MVC per "risposta gia' gestita a mano,
     * nessuna vista da renderizzare" (vedi Javadoc di
     * {@code ServletResponseMethodArgumentResolver}): il ramo non-htmx,
     * ritornando {@code "redirect:/gallery"} (non null), fa comunque
     * renderizzare il redirect esattamente come prima — nessun conflitto
     * tra i due rami nello stesso metodo.
     */
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id,
                          @RequestHeader(value = "HX-Request", required = false) String hxRequest,
                          HttpServletRequest request,
                          HttpServletResponse response) {
        generationService.delete(id);
        boolean isHtmxRequest = "true".equalsIgnoreCase(hxRequest);
        if (isHtmxRequest) {
            response.setHeader("HX-Redirect", request.getContextPath() + "/gallery");
            return null;
        }
        return "redirect:/gallery";
    }
}
