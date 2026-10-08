package org.dual.hexa.core.secrets.adapter.in.scheduling;

import org.dual.hexa.core.events.port.in.ISystemEvents;
import org.dual.hexa.core.events.domain.CoreEventSource;
import org.dual.hexa.core.secrets.port.in.ISecrets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Esecuzione periodica del controllo di scadenza dei segreti ({@link ISecrets#checkExpiries}): all'avvio e ogni
 * {@code app.secrets.expiry-check-interval}. Gli avvisi di una stessa serie (stesso segreto, entro
 * {@code app.events.warning-series-window}) non producono toast ripetuti. Disattivabile con
 * {@code app.secrets.expiry-check-enabled=false} (i test chiamano {@link #sweep} direttamente).
 */
@Component
@ConditionalOnProperty(name = "app.secrets.expiry-check-enabled", havingValue = "true", matchIfMissing = true)
public class SecretExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(SecretExpiryScheduler.class);

    private final ISecrets secrets;
    private final ISystemEvents events;

    public SecretExpiryScheduler(ISecrets secrets, ISystemEvents events) {
        this.secrets = secrets;
        this.events = events;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        sweep();
    }

    @Scheduled(fixedDelayString = "${app.secrets.expiry-check-interval:1h}", initialDelayString = "${app.secrets.expiry-check-interval:1h}")
    public void sweep() {
        try {
            int warned = secrets.checkExpiries();
            if (warned > 0) {
                log.info("Controllo scadenza segreti: {} avvisi", warned);
            }
        } catch (RuntimeException e) {
            events.record(CoreEventSource.SECRETS, "secretExpiryCheck", e);
        }
    }
}
