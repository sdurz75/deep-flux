package org.dual.replicate.controller;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;
import org.dual.replicate.core.events.domain.EventLink;
import org.dual.replicate.core.events.port.out.IEventLinkResolver;
import org.dual.replicate.domain.SystemEvent;
import org.dual.replicate.domain.SystemEventSeverity;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.dual.replicate.repository.SystemEventRepository;
import org.dual.replicate.service.SystemEventService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Registro degli eventi di sistema (SystemEventService: errori delle chiamate remote e interni, avvisi come la
 * scadenza dei token): listato paginato, piu' recente prima, filtrabile per severita', con svuotamento; e la campanella
 * della toolbar (eventi non visualizzati). Stesso pattern pagina/fragment di GalleryController.
 */
@Controller
public class SystemEventController {

    private static final int PAGE_SIZE = 20;

    private final SystemEventRepository repository;
    private final SystemEventService events;
    private final Messages messages;
    private final ObjectProvider<IEventLinkResolver> linkResolver;

    public SystemEventController(SystemEventRepository repository, SystemEventService events, Messages messages,
                                 ObjectProvider<IEventLinkResolver> linkResolver) {
        this.repository = repository;
        this.events = events;
        this.messages = messages;
        this.linkResolver = linkResolver;
    }

    /** Vecchio indirizzo del registro (segnalibri): il nome di vista "redirect:" applica il context path/prefisso del proxy. */
    @GetMapping("/errors")
    public String legacyPath() {
        return "redirect:/system/events";
    }

    /**
     * {@code severity} (ERROR|WARNING) filtra; {@code event} e' l'evento aperto dalla campanella: lo marca come visualizzato
     * (idempotente) e la pagina lo evidenzia. Aprire la pagina senza {@code event} non marca nulla.
     */
    @GetMapping("/system/events")
    public String list(@RequestParam(defaultValue = "1") int page,
                        @RequestParam(required = false) String severity,
                        @RequestParam(required = false) Long event,
                        @RequestHeader(value = "HX-Request", required = false) String hxRequest,
                        Model model) {
        if (event != null) {
            events.markSeen(event);
        }
        populate(page, severity, event, model);
        return "true".equalsIgnoreCase(hxRequest) ? contentView() : "system-events";
    }

    /** Svuota il registro e ritorna il contenuto aggiornato (target #events-content). */
    @PostMapping("/system/events/clear")
    public String clear(Model model) {
        repository.deleteAllInBatch();
        populate(1, null, null, model);
        return contentView();
    }

    /** Contenuto della campanella (pannello + badge): ricaricato dal contenitore statico dell'header. */
    @GetMapping("/system/events/bell")
    public String bell(Model model) {
        populateBell(model);
        return bellView();
    }

    /** "Segna tutti come letti": marca, aggiorna la campanella e avvisa la lista (evento client system-event). */
    @PostMapping("/system/events/seen")
    public String markAllSeen(HttpServletResponse response, Model model) {
        events.markAllSeen();
        events.addHxTrigger(response, "system-event", "");
        populateBell(model);
        return bellView();
    }

    private void populateBell(Model model) {
        SystemEventService.Unseen unseen = events.unseen();
        Instant now = Instant.now();
        model.addAttribute("bellCount", unseen.count());
        model.addAttribute("bellHasError", unseen.hasError());
        model.addAttribute("bellItems", unseen.latest().stream().map(e -> toBellItem(e, now)).toList());
    }

    /** Riga della campanella: gia' tradotta/formattata, nessuna logica nel template. */
    public record BellItem(Long id, String source, SystemEventSeverity severity, String message, String ago) {
    }

    private BellItem toBellItem(SystemEvent e, Instant now) {
        return new BellItem(e.getId(), e.getSource(), e.getSeverity(), e.getMessage(), ago(e.getLastSeenAt(), now));
    }

    String ago(Instant at, Instant now) {
        Duration elapsed = Duration.between(at, now);
        if (elapsed.toMinutes() < 1) {
            return messages.get("bell.ago.now");
        }
        if (elapsed.toHours() < 1) {
            return messages.get("bell.ago.minutes", elapsed.toMinutes());
        }
        if (elapsed.toDays() < 1) {
            return messages.get("bell.ago.hours", elapsed.toHours());
        }
        return messages.get("bell.ago.days", elapsed.toDays());
    }

    private void populate(int page, String severityParam, Long highlightId, Model model) {
        SystemEventSeverity severity = parseSeverity(severityParam);
        int pageIndex = Math.max(0, page - 1);
        Page<SystemEvent> result = find(severity, pageIndex);
        // Come GalleryController: una pagina che non esiste piu' (dopo un refresh) ricade sull'ultima esistente.
        if (result.isEmpty() && result.getTotalPages() > 0 && pageIndex >= result.getTotalPages()) {
            pageIndex = result.getTotalPages() - 1;
            result = find(severity, pageIndex);
        }
        int currentPage = pageIndex + 1;
        model.addAttribute("events", result.getContent());
        model.addAttribute("eventLinks", linksOf(result.getContent()));
        model.addAttribute("severity", severity == null ? null : severity.name());
        model.addAttribute("highlightId", highlightId);
        model.addAttribute("unseenCount", events.unseen().count());
        model.addAttribute("currentPage", currentPage);
        model.addAttribute("totalPages", result.getTotalPages());
        model.addAttribute("hasPrevious", result.hasPrevious());
        model.addAttribute("hasNext", result.hasNext());
        model.addAttribute("pageNumbers", PaginationSupport.window(currentPage, result.getTotalPages()));
    }

    /** I link "apri" per evento (id -> link), risolti dall'app tramite {@link IEventLinkResolver}; nessuna implementazione = nessun link. */
    private Map<Long, List<EventLink>> linksOf(List<SystemEvent> content) {
        IEventLinkResolver resolver = linkResolver.getIfAvailable();
        Map<Long, List<EventLink>> links = new HashMap<>();
        if (resolver != null) {
            content.forEach(e -> links.put(e.getId(), resolver.resolve(e.getSubject())));
        }
        return links;
    }

    private Page<SystemEvent> find(SystemEventSeverity severity, int pageIndex) {
        PageRequest pageable = PageRequest.of(pageIndex, PAGE_SIZE);
        return severity == null
                ? repository.findAllByOrderByLastSeenAtDesc(pageable)
                : repository.findAllBySeverityOrderByLastSeenAtDesc(severity, pageable);
    }

    /** Un valore sconosciuto equivale a "nessun filtro" (mai un 400 per un link vecchio). */
    private static SystemEventSeverity parseSeverity(String value) {
        if (value == null) {
            return null;
        }
        try {
            return SystemEventSeverity.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // Viste di risposta diretta: parametri NOMINATI (vedi CLAUDE.md, "Pattern controller").
    private static String contentView() {
        return "fragments/system-events :: content(events=${events}, eventLinks=${eventLinks}, currentPage=${currentPage}, totalPages=${totalPages}, "
                + "hasPrevious=${hasPrevious}, hasNext=${hasNext}, pageNumbers=${pageNumbers}, severity=${severity}, "
                + "highlightId=${highlightId}, unseenCount=${unseenCount})";
    }

    private static String bellView() {
        return "fragments/notification-bell :: bell(count=${bellCount}, hasError=${bellHasError}, items=${bellItems})";
    }
}
