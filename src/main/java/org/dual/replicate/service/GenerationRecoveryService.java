package org.dual.replicate.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.dual.replicate.app.AppEventSubjects;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.dual.replicate.core.events.domain.CoreEventSource;
import org.dual.replicate.domain.Generation;
import org.dual.replicate.domain.GenerationStatus;
import org.dual.replicate.repository.GenerationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Rete di sicurezza: nessuna generazione resta "in corso" (o senza il suo turno di chat)
 * per sempre, qualunque cosa sia andata storta (riavvio a meta' polling, watcher morto,
 * errore inatteso, nessun client a fare polling).
 * <ul>
 *   <li><b>All'avvio</b> (nessuna concorrenza: watcher e poller sono spariti col processo): ogni riga
 *       PENDING/PROCESSING viene fatta avanzare ({@link GenerationService#refresh}: scarica se pronta,
 *       fallisce se scaduta o cancellata); se e' ancora in corso ed e' di una conversazione di /deep-chat
 *       si riavvia il watcher perso.</li>
 *   <li><b>Periodicamente</b>: solo le righe OLTRE il proprio timeout di business (a quel punto watcher e
 *       poller sono comunque irrilevanti, nessuna corsa con loro), piu' i turni di chat mancanti di
 *       generazioni terminali.</li>
 * </ul>
 * Disattivabile con {@code app.recovery.enabled=false} (i test, che non devono toccare il ReplicateClient reale).
 */
@Service
@ConditionalOnProperty(name = "app.recovery.enabled", havingValue = "true", matchIfMissing = true)
public class GenerationRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(GenerationRecoveryService.class);

    private static final List<GenerationStatus> IN_PROGRESS = List.of(GenerationStatus.PENDING, GenerationStatus.PROCESSING);
    private static final List<GenerationStatus> TERMINAL = List.of(GenerationStatus.SUCCEEDED, GenerationStatus.FAILED);
    /** Un turno di esito piu' recente di cosi' potrebbe essere in scrittura proprio ora da un watcher. */
    private static final Duration TURN_GRACE = Duration.ofMinutes(2);

    private final GenerationRepository repository;
    private final GenerationService generationService;
    private final DeepChatGenerationWatcher watcher;
    private final ISystemEvents systemEvents;

    public GenerationRecoveryService(GenerationRepository repository, GenerationService generationService,
                                      DeepChatGenerationWatcher watcher, ISystemEvents systemEvents) {
        this.repository = repository;
        this.generationService = generationService;
        this.watcher = watcher;
        this.systemEvents = systemEvents;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        List<Generation> pending = repository.findByStatusIn(IN_PROGRESS);
        if (!pending.isEmpty()) {
            log.info("Recupero all'avvio: {} generazioni in corso da verificare", pending.size());
        }
        pending.forEach(generation -> recover(generation, true));
        writeMissingChatTurns();
    }

    @Scheduled(fixedDelayString = "${app.recovery.sweep-interval:2m}", initialDelayString = "${app.recovery.sweep-interval:2m}")
    public void sweep() {
        try {
            repository.findByStatusIn(IN_PROGRESS).stream()
                    .filter(generationService::isOverdue)
                    .forEach(generation -> recover(generation, false));
            writeMissingChatTurns();
        } catch (RuntimeException e) {
            systemEvents.record(CoreEventSource.INTERNAL, "recoverySweep", e);
        }
    }

    private void recover(Generation generation, boolean restartWatcher) {
        try {
            Generation refreshed = generationService.refresh(generation.getId());
            if (refreshed.isTerminal()) {
                if (refreshed.getConversationId() != null) {
                    watcher.persistOutcome(refreshed, refreshed.getConversationId());
                }
            } else if (restartWatcher && refreshed.getConversationId() != null) {
                watcher.watch(refreshed.getId(), refreshed.getConversationId(), Locale.ITALIAN);
            }
        } catch (RuntimeException e) {
            if (generationService.exists(generation.getId())) {
                systemEvents.record(CoreEventSource.INTERNAL, "recoverGeneration", e, AppEventSubjects.of(generation.getId(), generation.getConversationId()));
            }
        }
    }

    private void writeMissingChatTurns() {
        for (Generation generation : repository.findTerminalWithoutChatTurn(TERMINAL, Instant.now().minus(TURN_GRACE))) {
            watcher.persistOutcome(generation, generation.getConversationId());
        }
    }
}
