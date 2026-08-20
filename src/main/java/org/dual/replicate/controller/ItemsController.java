package org.dual.replicate.controller;

import java.util.List;
import java.util.stream.IntStream;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Pattern "load more": stessa URL, due risposte (come SearchController).
 * Navigazione normale (nav link, refresh, bookmark) -> pagina intera con
 * la prima pagina di risultati gia' incorporata. Click sul pulsante
 * "Carica altri" (richiesta htmx, header HX-Request) -> solo i nuovi
 * <li> da appendere in coda alla lista esistente.
 */
@Controller
@RequestMapping("/items")
public class ItemsController {

    private static final int PAGE_SIZE = 10;

    private static final List<String> ALL_ITEMS = IntStream.rangeClosed(1, 47)
            .mapToObj(i -> "Item " + i)
            .toList();

    @GetMapping
    public String list(@RequestParam(defaultValue = "0") int page,
                        @RequestHeader(value = "HX-Request", required = false) String hxRequest,
                        Model model) {

        List<String> pageItems = ALL_ITEMS.stream()
                .skip((long) page * PAGE_SIZE)
                .limit(PAGE_SIZE)
                .toList();

        boolean hasMore = (long) (page + 1) * PAGE_SIZE < ALL_ITEMS.size();

        model.addAttribute("items", pageItems);
        model.addAttribute("nextPage", page + 1);
        model.addAttribute("hasMore", hasMore);

        boolean isHtmxRequest = "true".equalsIgnoreCase(hxRequest);
        return isHtmxRequest
                ? "fragments/items :: rows(${items}, ${nextPage}, ${hasMore})"
                : "items";
    }
}
