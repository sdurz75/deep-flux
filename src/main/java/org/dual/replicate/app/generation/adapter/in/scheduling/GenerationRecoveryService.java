package org.dual.replicate.app.generation.adapter.in.scheduling;

import java.util.List;

import org.dual.replicate.app.generation.port.in.IGenerations;
import org.dual.replicate.app.AppStartupOrder;
import org.dual.replicate.app.shared.domain.AppEventSubjects;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.dual.replicate.core.events.domain.CoreEventSource;
import org.dual.replicate.app.generation.domain.Generation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Rete di sicurezza: nessuna generazione resta "in corso" per sempre, qualunque cosa sia andata storta (riavvio a meta' polling,
 * errore inatteso, nessun client a fare polling).
 * <ul>
 *   <li><b>All'avvio</b> (nessuna concorrenza: watcher e poller sono spariti col processo): ogni riga PENDING/PROCESSING viene
 *       fatta avanzare ({@link IGenerations#refresh}: scarica se pronta, fallisce se scaduta o cancellata).</li>
 *   <li><b>Periodicamente</b>: solo le righe OLTRE il proprio timeout di business (a quel punto watcher e poller sono comunque
 *       irrilevanti, nessuna corsa con loro).</li>
 * </ul>
 * La parte di chat (riavvio dei watcher persi, turni di esito mancanti) e' di {@code ChatRecoveryService}, che all'avvio gira
 * DOPO questo (ordine dei listener). Disattivabile con {@code app.recovery.enabled=false} (i test, che non devono toccare il
 * ReplicateClient reale).
 */
@Service
@ConditionalOnProperty(name = "app.recovery.enabled", havingValue = "true", matchIfMissing = true)
public class GenerationRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(GenerationRecoveryService.class);

    private final IGenerations generationService;
    private final ISystemEvents systemEvents;

    public GenerationRecoveryService(IGenerations generationService, ISystemEvents systemEvents) {
        this.generationService = generationService;
        this.systemEvents = systemEvents;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(AppStartupOrder.GENERATION_RECOVERY)
    public void recoverOnStartup() {
        List<Generation> pending = generationService.inProgress();
        if (!pending.isEmpty()) {
            log.info("Recupero all'avvio: {} generazioni in corso da verificare", pending.size());
        }
        pending.forEach(this::recover);
    }

    @Scheduled(fixedDelayString = "${app.recovery.sweep-interval:2m}", initialDelayString = "${app.recovery.sweep-interval:2m}")
    public void sweep() {
        try {
            generationService.inProgress().stream()
                    .filter(generationService::isOverdue)
                    .forEach(this::recover);
        } catch (RuntimeException e) {
            systemEvents.record(CoreEventSource.INTERNAL, "recoverySweep", e);
        }
    }

    private void recover(Generation generation) {
        try {
            generationService.refresh(generation.getId());
        } catch (RuntimeException e) {
            if (generationService.exists(generation.getId())) {
                systemEvents.record(CoreEventSource.INTERNAL, "recoverGeneration", e, AppEventSubjects.of(generation.getId(), generation.getConversationId()));
            }
        }
    }
}
