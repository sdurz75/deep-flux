package org.dual.replicate.app.training.adapter.in.scheduling;

import org.dual.replicate.app.AppStartupOrder;
import org.dual.replicate.app.training.port.in.ICaptionJobs;
import org.dual.replicate.core.events.domain.CoreEventSource;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Rete di sicurezza dei lavori di addestramento: nessuna didascalia resta "in corso" per sempre (riavvio a meta' lavoro, evento perso, coda piena).
 * All'avvio ogni didascalia in sospeso si rilancia (i lavori in corso sono spariti col processo); poi, a intervalli, di nuovo: il lavoro e' idempotente e
 * ricorda quali immagini ha in corso, quindi un rilancio non ne duplica una gia' partita. Disattivabile con {@code app.recovery.enabled=false} (i test e
 * il profilo {@code backup}, che non devono avviare nulla di nascosto).
 */
@Service
@ConditionalOnProperty(name = "app.recovery.enabled", havingValue = "true", matchIfMissing = true)
public class TrainingRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(TrainingRecoveryService.class);

    private final ICaptionJobs captionJobs;
    private final ISystemEvents systemEvents;

    public TrainingRecoveryService(ICaptionJobs captionJobs, ISystemEvents systemEvents) {
        this.captionJobs = captionJobs;
        this.systemEvents = systemEvents;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(AppStartupOrder.TRAINING_RECOVERY)
    public void recoverOnStartup() {
        recoverCaptions();
    }

    @Scheduled(fixedDelayString = "${app.training.caption-sweep-interval:5m}", initialDelayString = "${app.training.caption-sweep-interval:5m}")
    public void sweep() {
        recoverCaptions();
    }

    private void recoverCaptions() {
        try {
            int restarted = captionJobs.recoverPending();
            if (restarted > 0) {
                log.info("Recupero: {} didascalie di addestramento rilanciate", restarted);
            }
        } catch (RuntimeException e) {
            systemEvents.record(CoreEventSource.INTERNAL, "recoverTrainingCaptions", e);
        }
    }
}
