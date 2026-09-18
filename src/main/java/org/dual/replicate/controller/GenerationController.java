package org.dual.replicate.controller;

import org.dual.replicate.domain.Generation;
import org.dual.replicate.replicate.ReplicateException;
import org.dual.replicate.service.GenerationService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Creazione di una generazione e polling del suo stato. Il polling segue
 * il pattern "stessa URL, due risposte": GET /generations/{id} fa
 * avanzare lo stato e risponde con il solo fragment quando chiamato da
 * htmx (hx-trigger="every 2s"), con la pagina intera altrimenti — che
 * include un meta-refresh come fallback per la navigazione senza JS.
 */
@Controller
@RequestMapping("/generations")
public class GenerationController {

    private final GenerationService generationService;

    public GenerationController(GenerationService generationService) {
        this.generationService = generationService;
    }

    @GetMapping("/new")
    public String form(@RequestParam(required = false) String prompt, Model model) {
        model.addAttribute("prompt", prompt);
        return "generate";
    }

    @PostMapping
    public String create(@RequestParam String model,
                          @RequestParam(required = false) String version,
                          @RequestParam String prompt,
                          @RequestParam(required = false) String parameters,
                          @RequestHeader(value = "HX-Request", required = false) String hxRequest,
                          Model uiModel) {
        boolean isHtmxRequest = "true".equalsIgnoreCase(hxRequest);
        try {
            Generation generation = generationService.create(model, version, prompt, parameters);
            if (isHtmxRequest) {
                uiModel.addAttribute("generation", generation);
                return "fragments/generation :: status";
            }
            return "redirect:/generations/" + generation.getId();
        } catch (ReplicateException e) {
            uiModel.addAttribute("error", e.getMessage());
            uiModel.addAttribute("model", model);
            uiModel.addAttribute("version", version);
            uiModel.addAttribute("prompt", prompt);
            uiModel.addAttribute("parameters", parameters);
            return isHtmxRequest ? "fragments/generate-form :: form" : "generate";
        }
    }

    @GetMapping("/{id}")
    public String status(@PathVariable Long id,
                          @RequestHeader(value = "HX-Request", required = false) String hxRequest,
                          Model model) {
        Generation generation = generationService.refresh(id);
        model.addAttribute("generation", generation);

        boolean isHtmxRequest = "true".equalsIgnoreCase(hxRequest);
        return isHtmxRequest ? "fragments/generation :: status" : "generation-status";
    }
}
