package org.dual.replicate.core.manual.adapter.in.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.dual.replicate.core.manual.domain.ManualEntry;
import org.dual.replicate.core.manual.domain.ManualPage;
import org.dual.replicate.core.manual.port.in.IManual;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

/**
 * Il manuale online: l'indice ({@code /manual}) e una pagina ({@code /manual/{slug}}). Solo navigazione, nessun fragment htmx. Lo slug si risolve
 * dall'indice di {@code IManual}, mai come percorso; uno slug sconosciuto e' un 404 (non una riga del registro eventi). I link dentro l'HTML sono
 * riscritti col context path della richiesta, perche' quell'HTML non passa da {@code @{...}}.
 */
@Controller
public class ManualController {

    /** Un gruppo di pagine con la sua etichetta (chiave {@code manual.group.<cartella>} del bundle dell'app; senza, il nome della cartella). */
    public record Group(String label, List<ManualEntry> entries) {
    }

    private final IManual manual;
    private final Messages messages;

    public ManualController(IManual manual, Messages messages) {
        this.manual = manual;
        this.messages = messages;
    }

    @GetMapping("/manual")
    public String index(Model model) {
        List<ManualEntry> entries = manual.contents(language());
        model.addAttribute("groups", groups(entries));
        model.addAttribute("page", null);
        return "core/manual";
    }

    @GetMapping("/manual/{slug}")
    public String page(@PathVariable String slug, HttpServletRequest request, Model model) {
        String language = language();
        ManualPage page = manual.page(slug, language, request.getContextPath())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, messages.get("manual.error.pageNotFound")));
        List<ManualEntry> entries = manual.contents(language);
        int at = entries.indexOf(page.entry());
        model.addAttribute("groups", groups(entries));
        model.addAttribute("page", page);
        // Il riquadro "In questa pagina" va dopo il titolo, non prima: l'HTML si spezza al primo </h1> (se manca, tutto e' corpo).
        int endOfTitle = page.html().indexOf("</h1>");
        int split = endOfTitle < 0 ? 0 : endOfTitle + "</h1>".length();
        model.addAttribute("pageHead", page.html().substring(0, split));
        model.addAttribute("pageBody", page.html().substring(split));
        model.addAttribute("previous", at > 0 ? entries.get(at - 1) : null);
        model.addAttribute("next", at >= 0 && at + 1 < entries.size() ? entries.get(at + 1) : null);
        return "core/manual";
    }

    private static String language() {
        return LocaleContextHolder.getLocale().getLanguage();
    }

    private List<Group> groups(List<ManualEntry> entries) {
        Map<String, List<ManualEntry>> byGroup = new LinkedHashMap<>();
        for (ManualEntry entry : entries) {
            byGroup.computeIfAbsent(entry.group(), g -> new ArrayList<>()).add(entry);
        }
        List<Group> groups = new ArrayList<>();
        byGroup.forEach((group, list) -> groups.add(new Group(messages.getOrDefault("manual.group." + group, group), List.copyOf(list))));
        return groups;
    }
}
