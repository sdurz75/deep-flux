package org.dual.hexa.core.lock.adapter.in.web;

import org.dual.hexa.core.lock.port.in.ILock;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Il timeout per lo script di inattivita' ({@code fragments/core/lock-idle.html}). */
@ControllerAdvice
class LockModelAdvice {

    private final ILock lock;

    LockModelAdvice(ILock lock) {
        this.lock = lock;
    }

    @ModelAttribute("lockIdleSeconds")
    int lockIdleSeconds() {
        return lock.idleTimeoutSeconds();
    }
}
