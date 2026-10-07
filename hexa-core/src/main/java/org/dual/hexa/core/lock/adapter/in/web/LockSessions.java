package org.dual.hexa.core.lock.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.dual.hexa.core.lock.port.in.ILock;
import org.springframework.stereotype.Component;

/**
 * Lo stato di sblocco sta nella SESSIONE HTTP (un'altra sessione, cioe' un altro browser, parte sempre bloccata). Si memorizza l'istante dell'ultimo input
 * dell'utente: lo aggiornano solo lo sblocco e {@code POST /lock/touch}, che il client invia per un input VERO. Le richieste automatiche (polling delle
 * generazioni, barra crediti, SSE) non devono contare, altrimenti l'app non si bloccherebbe mai. Scaduto il timeout piu' una tolleranza, la sessione e'
 * bloccata anche se il client non ha fatto nulla (JavaScript fermo, scheda in background).
 */
@Component
class LockSessions {

    static final String ATTRIBUTE = "hexa.lock.lastInput";
    /** Tolleranza sul timeout: il client invia i touch al massimo ogni 30 s (e ogni terzo del timeout). */
    static final long GRACE_MILLIS = 30_000;

    private final ILock lock;

    LockSessions(ILock lock) {
        this.lock = lock;
    }

    boolean isUnlocked(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || !(session.getAttribute(ATTRIBUTE) instanceof Long lastInput)) {
            return false;
        }
        return System.currentTimeMillis() - lastInput <= lock.idleTimeoutSeconds() * 1000L + GRACE_MILLIS;
    }

    /** Dopo uno sblocco riuscito: nuovo id di sessione (niente fissazione) e primo istante di attivita'. */
    void unlock(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        session.setAttribute(ATTRIBUTE, System.currentTimeMillis());
    }

    void touch(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.setAttribute(ATTRIBUTE, System.currentTimeMillis());
        }
    }

    void lock(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(ATTRIBUTE);
        }
    }
}
