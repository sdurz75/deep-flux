package org.dual.hexa.core.lock.adapter.in.web;

import org.dual.hexa.core.kernel.i18n.Messages;
import org.dual.hexa.core.lock.domain.LockException;
import org.dual.hexa.core.lock.port.in.ILock;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * La pagina «Sicurezza»: imposta, cambia e toglie il PIN e il timeout di inattivita'. Stessa URL, due risposte distinte da {@code HX-Request} (pagina
 * intera o solo il pannello). Ogni modifica a un blocco gia' attivo richiede il PIN attuale: chi trova l'app sbloccata non puo' toglierlo.
 */
@Controller
class SecurityController {

    private static final String PANEL = "fragments/core/security-panel :: panel(enabled=${enabled}, timeout=${timeout}, timeouts=${timeouts}, error=${error}, saved=${saved})";

    private final ILock lock;
    private final Messages messages;

    SecurityController(ILock lock, Messages messages) {
        this.lock = lock;
        this.messages = messages;
    }

    @GetMapping("/security")
    String page(Model model, @RequestHeader(value = "HX-Request", required = false) String htmx) {
        return render(model, htmx, null, false);
    }

    @PostMapping("/security/pin")
    String enable(@RequestParam(defaultValue = "") String pin, @RequestParam(defaultValue = "") String confirm,
                  @RequestParam(defaultValue = "0") int timeout, Model model, @RequestHeader(value = "HX-Request", required = false) String htmx) {
        return act(model, htmx, () -> {
            requireSame(pin, confirm);
            lock.enable(pin, timeout);
        });
    }

    @PostMapping("/security/pin/change")
    String changePin(@RequestParam(defaultValue = "") String current, @RequestParam(defaultValue = "") String pin,
                     @RequestParam(defaultValue = "") String confirm, Model model, @RequestHeader(value = "HX-Request", required = false) String htmx) {
        return act(model, htmx, () -> {
            requireSame(pin, confirm);
            lock.changePin(current, pin);
        });
    }

    @PostMapping("/security/timeout")
    String changeTimeout(@RequestParam(defaultValue = "") String current, @RequestParam(defaultValue = "0") int timeout, Model model,
                         @RequestHeader(value = "HX-Request", required = false) String htmx) {
        return act(model, htmx, () -> lock.changeIdleTimeout(current, timeout));
    }

    @PostMapping("/security/disable")
    String disable(@RequestParam(defaultValue = "") String current, Model model, @RequestHeader(value = "HX-Request", required = false) String htmx) {
        return act(model, htmx, () -> lock.disable(current));
    }

    private String act(Model model, String htmx, Runnable action) {
        try {
            action.run();
        } catch (LockException e) {
            // Rifiuto atteso (PIN errato, formato, rallentamento): messaggio nel pannello, nessuna riga nel registro eventi.
            return render(model, htmx, e.getMessage(), false);
        }
        return render(model, htmx, null, true);
    }

    private void requireSame(String pin, String confirm) {
        if (!pin.equals(confirm)) {
            throw new LockException(messages.get("lock.error.confirmMismatch"));
        }
    }

    private String render(Model model, String htmx, String error, boolean saved) {
        model.addAttribute("enabled", lock.isEnabled());
        model.addAttribute("timeout", lock.idleTimeoutSeconds());
        model.addAttribute("timeouts", ILock.ALLOWED_TIMEOUTS);
        model.addAttribute("error", error);
        model.addAttribute("saved", saved);
        return htmx != null ? PANEL : "core/security";
    }
}
