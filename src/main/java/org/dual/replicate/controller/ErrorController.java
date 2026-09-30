package org.dual.replicate.controller;

import org.dual.replicate.domain.AppError;
import org.dual.replicate.repository.AppErrorRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Registro degli errori delle chiamate remote e interni (AppErrorService): listato paginato, piu'
 * recente prima, con svuotamento. Stesso pattern pagina/fragment di GalleryController.
 */
@Controller
@RequestMapping("/errors")
public class ErrorController {

    private static final int PAGE_SIZE = 20;

    private final AppErrorRepository repository;

    public ErrorController(AppErrorRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public String list(@RequestParam(defaultValue = "1") int page,
                        @RequestHeader(value = "HX-Request", required = false) String hxRequest,
                        Model model) {
        populate(page, model);
        return "true".equalsIgnoreCase(hxRequest) ? contentView() : "errors";
    }

    /** Svuota il registro e ritorna il contenuto aggiornato (target #errors-content). */
    @PostMapping("/clear")
    public String clear(Model model) {
        repository.deleteAllInBatch();
        populate(1, model);
        return contentView();
    }

    private void populate(int page, Model model) {
        int pageIndex = Math.max(0, page - 1);
        Page<AppError> result = repository.findAllByOrderByLastSeenAtDesc(PageRequest.of(pageIndex, PAGE_SIZE));
        // Come GalleryController: una pagina che non esiste piu' (dopo un refresh) ricade sull'ultima esistente.
        if (result.isEmpty() && result.getTotalPages() > 0 && pageIndex >= result.getTotalPages()) {
            pageIndex = result.getTotalPages() - 1;
            result = repository.findAllByOrderByLastSeenAtDesc(PageRequest.of(pageIndex, PAGE_SIZE));
        }
        int currentPage = pageIndex + 1;
        model.addAttribute("errors", result.getContent());
        model.addAttribute("currentPage", currentPage);
        model.addAttribute("totalPages", result.getTotalPages());
        model.addAttribute("hasPrevious", result.hasPrevious());
        model.addAttribute("hasNext", result.hasNext());
        model.addAttribute("pageNumbers", PaginationSupport.window(currentPage, result.getTotalPages()));
    }

    // Vista di risposta diretta: parametri NOMINATI (vedi CLAUDE.md, "Pattern controller").
    private static String contentView() {
        return "fragments/errors :: content(errors=${errors}, currentPage=${currentPage}, totalPages=${totalPages}, "
                + "hasPrevious=${hasPrevious}, hasNext=${hasNext}, pageNumbers=${pageNumbers})";
    }
}
