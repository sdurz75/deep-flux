package org.dual.replicate.service;

import org.dual.replicate.domain.SystemEventSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Esecuzione periodica del controllo di scadenza dei token ({@link ApiTokenService#checkExpiries}): all'avvio e ogni
 * {@code app.tokens.expiry-check-interval}. Gli avvisi di una stessa serie (stesso token, entro
 * {@code app.events.warning-series-window}) non producono toast ripetuti. Disattivabile con
 * {@code app.tokens.expiry-check-enabled=false} (i test chiamano {@link #sweep} direttamente).
 */
@Component
@ConditionalOnProperty(name = "app.tokens.expiry-check-enabled", havingValue = "true", matchIfMissing = true)
public class ApiTokenExpiryService {

    private static final Logger log = LoggerFactory.getLogger(ApiTokenExpiryService.class);

    private final ApiTokenService tokens;
    private final SystemEventService events;

    public ApiTokenExpiryService(ApiTokenService tokens, SystemEventService events) {
        this.tokens = tokens;
        this.events = events;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        sweep();
    }

    @Scheduled(fixedDelayString = "${app.tokens.expiry-check-interval:1h}", initialDelayString = "${app.tokens.expiry-check-interval:1h}")
    public void sweep() {
        try {
            int warned = tokens.checkExpiries();
            if (warned > 0) {
                log.info("Controllo scadenza token: {} avvisi", warned);
            }
        } catch (RuntimeException e) {
            events.record(SystemEventSource.TOKENS, "tokenExpiryCheck", e);
        }
    }
}
