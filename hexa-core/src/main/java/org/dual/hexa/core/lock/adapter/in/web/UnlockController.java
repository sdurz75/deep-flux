package org.dual.hexa.core.lock.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.dual.hexa.core.lock.domain.LockException;
import org.dual.hexa.core.lock.port.in.ILock;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Sblocco con il PIN, blocco immediato e «touch» dell'attivita'. {@code next} e' l'URL a cui tornare: solo un path dell'app (si accetta {@code /...}, mai
 * {@code //host} o schemi), altrimenti la home.
 */
@Controller
class UnlockController {

    private final ILock lock;
    private final LockSessions sessions;

    UnlockController(ILock lock, LockSessions sessions) {
        this.lock = lock;
        this.sessions = sessions;
    }

    @GetMapping("/unlock")
    Object page(@RequestParam(defaultValue = "/") String next, HttpServletRequest request, Model model) {
        if (!lock.isEnabled() || sessions.isUnlocked(request)) {
            return redirect(request, next);
        }
        model.addAttribute("next", safeNext(next));
        model.addAttribute("error", null);
        return "core/unlock";
    }

    @PostMapping("/unlock")
    Object unlock(@RequestParam(defaultValue = "") String pin, @RequestParam(defaultValue = "/") String next, HttpServletRequest request, Model model) {
        try {
            lock.verify(pin);
        } catch (LockException e) {
            // Rifiuto atteso (PIN errato o rallentamento): messaggio nel form, mai un errore HTTP ne' una riga nel registro eventi.
            model.addAttribute("next", safeNext(next));
            model.addAttribute("error", e.getMessage());
            return "core/unlock";
        }
        sessions.unlock(request);
        return redirect(request, next);
    }

    /** Blocca la sessione subito. Esente dal cancello: bloccare una sessione gia' bloccata e' un no-op. Da fetch risponde 204, da form torna allo sblocco. */
    @PostMapping("/lock/now")
    ResponseEntity<Void> lockNow(HttpServletRequest request) {
        sessions.lock(request);
        String accept = request.getHeader("Accept");
        if (accept != null && accept.contains("text/html")) {
            return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(request.getContextPath() + "/unlock")).build();
        }
        return ResponseEntity.noContent().build();
    }

    /** L'utente ha dato un input vero: allunga la sessione sbloccata. Il cancello risponde 401 se e' gia' scaduta. */
    @PostMapping("/lock/touch")
    ResponseEntity<Void> touch(HttpServletRequest request) {
        sessions.touch(request);
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<Void> redirect(HttpServletRequest request, String next) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(request.getContextPath() + safeNext(next))).build();
    }

    static String safeNext(String next) {
        if (next == null || !next.startsWith("/") || next.startsWith("//") || next.contains("\\") || next.contains("\r") || next.contains("\n")) {
            return "/";
        }
        return next;
    }
}
