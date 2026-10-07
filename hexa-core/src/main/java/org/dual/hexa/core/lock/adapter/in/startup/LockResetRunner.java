package org.dual.hexa.core.lock.adapter.in.startup;

import org.dual.hexa.core.events.domain.CoreEventSource;
import org.dual.hexa.core.events.port.in.ISystemEvents;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.dual.hexa.core.lock.port.in.ILock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Recupero di un PIN dimenticato: con {@code app.lock.reset=true} (es. {@code APP_LOCK_RESET=true} nel {@code .env}) all'avvio il blocco viene spento senza
 * PIN, e la cosa resta nel registro eventi. Chi puo' cambiare la configurazione del server puo' comunque leggere il database: non indebolisce il blocco.
 * Si toglie la proprieta' dopo l'uso, altrimenti ogni riavvio spegne il blocco.
 */
@Component
@Profile("!backup")
class LockResetRunner implements ApplicationRunner {

    private final ILock lock;
    private final ISystemEvents systemEvents;
    private final Messages messages;
    private final boolean reset;

    LockResetRunner(ILock lock, ISystemEvents systemEvents, Messages messages, @Value("${app.lock.reset:false}") boolean reset) {
        this.lock = lock;
        this.systemEvents = systemEvents;
        this.messages = messages;
        this.reset = reset;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!reset) {
            return;
        }
        boolean wasEnabled = lock.isEnabled();
        lock.reset();
        if (wasEnabled) {
            systemEvents.warn(CoreEventSource.INTERNAL, "lockReset", null, messages.get("lock.event.reset"));
        }
    }
}
