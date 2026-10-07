package org.dual.hexa.core.lock.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.dual.hexa.core.lock.port.in.ILock;
import org.dual.hexa.core.web.ILayoutContributor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Innesta il blocco nel layout del core: la voce «Sicurezza» nel menu «Gestione» e, solo se il PIN e' attivo e la sessione sbloccata, lo script che misura
 * l'inattivita' (mai sulla pagina di sblocco).
 */
@Component
@Order(20)
class LockLayoutContributor implements ILayoutContributor {

    private final ILock lock;
    private final LockSessions sessions;

    LockLayoutContributor(ILock lock, LockSessions sessions) {
        this.lock = lock;
        this.sessions = sessions;
    }

    @Override
    public List<String> bodyEnd(HttpServletRequest request) {
        return lock.isEnabled() && sessions.isUnlocked(request) ? List.of("fragments/core/lock-idle :: script") : List.of();
    }

    @Override
    public List<NavEntry> manageMenu() {
        return List.of(new NavEntry("/security", "lock.menu.security"));
    }
}
