package org.dual.replicate.core.events.adapter.in.web;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;
import org.dual.replicate.core.events.domain.EventLink;
import org.dual.replicate.core.events.port.out.IEventLinkResolver;
import org.dual.replicate.core.events.domain.EventPage;
import org.dual.replicate.core.events.domain.SystemEvent;
import org.dual.replicate.core.events.domain.SystemEventSeverity;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.dual.replicate.core.web.HtmxEvents;
import org.dual.replicate.core.web.PaginationSupport;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Registro degli eventi di sistema (ISystemEvents: errori delle chiamate remote e interni, avvisi come la
 * scadenza dei token): listato paginato, piu' recente prima, filtrabile per severita', con svuotamento; e la campanella
 * della toolbar (eventi non visualizzati). Stesso pattern pagina/fragment di GalleryController.
 */
@Controller
public class SystemEventController {

    private static final int PAGE_SIZE = 20;

    private final ISystemEvents events;
    private final HtmxEvents htmx;
    private final Messages messages;
    private final ObjectProvider<IEventLinkResolver> linkResolver;

    public SystemEventController(ISystemEvents events, HtmxEvents htmx, Messages messages,
                                 ObjectProvider<IEventLinkResolver> linkResolver) {
        this.events = events;
        this.htmx = htmx;
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
        return "true".equalsIgnoreCase(hxRequest) ? contentView() : "core/system-events";
    }

    /** Svuota il registro e ritorna il contenuto aggiornato (target #events-content). */
    @PostMapping("/system/events/clear")
    public String clear(Model model) {
        events.clear();
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
        htmx.addHxTrigger(response, "system-event", "");
        populateBell(model);
        return bellView();
    }

    private void populateBell(Model model) {
        ISystemEvents.Unseen unseen = events.unseen();
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
        EventPage result = events.list(severity, pageIndex, PAGE_SIZE);
        // Come GalleryController: una pagina che non esiste piu' (dopo un refresh) ricade sull'ultima esistente.
        if (result.isEmpty() && result.totalPages() > 0 && pageIndex >= result.totalPages()) {
            pageIndex = result.totalPages() - 1;
            result = events.list(severity, pageIndex, PAGE_SIZE);
        }
        int currentPage = pageIndex + 1;
        model.addAttribute("events", result.content());
        model.addAttribute("eventLinks", linksOf(result.content()));
        model.addAttribute("severity", severity == null ? null : severity.name());
        model.addAttribute("highlightId", highlightId);
        model.addAttribute("unseenCount", events.unseen().count());
        model.addAttribute("currentPage", currentPage);
        model.addAttribute("totalPages", result.totalPages());
        model.addAttribute("hasPrevious", result.hasPrevious());
        model.addAttribute("hasNext", result.hasNext());
        model.addAttribute("pageNumbers", PaginationSupport.window(currentPage, result.totalPages()));
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
        return "fragments/core/system-events :: content(events=${events}, eventLinks=${eventLinks}, currentPage=${currentPage}, totalPages=${totalPages}, "
                + "hasPrevious=${hasPrevious}, hasNext=${hasNext}, pageNumbers=${pageNumbers}, severity=${severity}, "
                + "highlightId=${highlightId}, unseenCount=${unseenCount})";
    }

    private static String bellView() {
        return "fragments/core/notification-bell :: bell(count=${bellCount}, hasError=${bellHasError}, items=${bellItems})";
    }
}
