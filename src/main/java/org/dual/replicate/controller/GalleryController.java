package org.dual.replicate.controller;

import org.dual.replicate.domain.Generation;
import org.dual.replicate.domain.GenerationStatus;
import org.dual.replicate.repository.GenerationRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * Galleria delle immagini generate: pattern "load more" identico a
 * ItemsController per la lista, pagina di dettaglio dedicata per
 * consultare prompt e parametri di una singola generazione.
 */
@Controller
@RequestMapping("/gallery")
public class GalleryController {

    private static final int PAGE_SIZE = 12;

    private final GenerationRepository repository;

    public GalleryController(GenerationRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public String list(@RequestParam(defaultValue = "0") int page,
                        @RequestHeader(value = "HX-Request", required = false) String hxRequest,
                        Model model) {
        var pageRequest = PageRequest.of(page, PAGE_SIZE);
        var result = repository.findByStatusOrderByCreatedAtDesc(GenerationStatus.SUCCEEDED, pageRequest);

        model.addAttribute("generations", result.getContent());
        model.addAttribute("nextPage", page + 1);
        model.addAttribute("hasMore", result.hasNext());

        boolean isHtmxRequest = "true".equalsIgnoreCase(hxRequest);
        return isHtmxRequest
                ? "fragments/gallery :: cards(${generations}, ${nextPage}, ${hasMore})"
                : "gallery";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Generation generation = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Generazione non trovata"));
        model.addAttribute("generation", generation);
        return "gallery-detail";
    }
}
