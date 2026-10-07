package org.dual.hexa.app.credits.adapter.in.web;

import java.math.BigDecimal;
import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;
import org.dual.hexa.app.credits.port.in.IReplicateBalance;
import org.dual.hexa.ai.credits.port.in.ICredits;
import org.dual.hexa.core.events.port.in.ISystemEvents;
import org.dual.hexa.core.kernel.remote.RemoteServiceException;
import org.dual.hexa.core.web.HtmxEvents;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Credito residuo nella barra in basso ({@code fragments/app/status-extras.html}, incluso dal layout del core). La pagina non fa mai
 * chiamate remote nel render: la barra si carica da {@code GET /credits/bar} (htmx, ogni pochi minuti e a fine generazione).
 */
@Controller
@RequestMapping("/credits")
public class CreditsController {

    private final ICredits credits;
    private final IReplicateBalance replicateBalance;
    private final ISystemEvents systemEvents;
    private final HtmxEvents htmx;

    public CreditsController(ICredits credits, IReplicateBalance balance, ISystemEvents systemEvents, HtmxEvents htmx) {
        this.credits = credits;
        this.replicateBalance = balance;
        this.systemEvents = systemEvents;
        this.htmx = htmx;
    }

    @GetMapping("/bar")
    public String bar(Model model) {
        return barView(model);
    }

    /** Imposta il saldo Replicate; risponde con la barra aggiornata. Un valore non valido e' un rifiuto con toast (nessuna riga di errore). */
    @PostMapping("/replicate")
    public String setReplicateBalance(@RequestParam(defaultValue = "") String balance, HttpServletResponse response, Model model) {
        try {
            replicateBalance.setReplicateBalance(parse(balance));
            htmx.addHxTrigger(response, "credits-saved", "");
        } catch (RemoteServiceException e) {
            if (e.isReportable()) {
                htmx.addToastHeader(response, systemEvents.record("setReplicateBalance", e));
            } else {
                // Rifiuto atteso (REJECTED): solo il messaggio, senza riga in system_event (stesso payload del toast del core).
                htmx.addHxTrigger(response, "system-toast", Map.of("key", "credits-balance-invalid", "message", e.getMessage(),
                        "transient", false, "severity", "WARNING"));
            }
        }
        return barView(model);
    }

    private String barView(Model model) {
        model.addAttribute("creditLines", credits.lines());
        return "fragments/app/status-extras :: items(creditLines=${creditLines})";
    }

    /** Accetta anche la virgola decimale; vuoto o non numerico = {@code null} (rifiutato dal servizio). */
    private static BigDecimal parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
