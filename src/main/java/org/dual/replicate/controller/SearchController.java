package org.dual.replicate.controller;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Pattern "stessa URL, due risposte": e' la tecnica hypermedia-first
 * vera e propria. Se la richiesta arriva da htmx (header HX-Request)
 * rispondiamo con il solo fragment dei risultati; se arriva da
 * navigazione normale (refresh, link diretto, bookmark) rispondiamo
 * con la pagina intera che incorpora lo stesso fragment. Nessuna
 * duplicazione di markup, un solo controller, un solo URL condivisibile.
 */
@Controller
@RequestMapping("/search")
public class SearchController {

    private static final List<String> CATALOG = List.of(
            "Spring Boot", "Spring MVC", "Thymeleaf", "htmx", "Alpine.js",
            "Maven", "Java", "CQRS", "Event Sourcing", "MongoDB",
            "Apache Artemis", "Docker Swarm"
    );

    @GetMapping
    public String search(@RequestParam(defaultValue = "") String q,
                          @RequestHeader(value = "HX-Request", required = false) String hxRequest,
                          Model model) {

        String query = q.trim();
        List<String> results = query.isBlank()
                ? List.of()
                : CATALOG.stream()
                    .filter(s -> s.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))
                    .toList();

        model.addAttribute("query", query);
        model.addAttribute("results", results);

        boolean isHtmxRequest = "true".equalsIgnoreCase(hxRequest);
        return isHtmxRequest ? "fragments/search :: results" : "search";
    }
}
