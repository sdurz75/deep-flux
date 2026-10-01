package org.dual.replicate.service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.dual.replicate.core.events.domain.CoreEventSource;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.dual.replicate.domain.Generation;
import org.dual.replicate.repository.ChatMessageRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Rete di sicurezza della chat: nessuna generazione avviata da /deep-chat resta senza il suo watcher o senza il suo turno di
 * esito. All'avvio (dopo che {@link GenerationRecoveryService} ha fatto avanzare le righe in corso) riavvia il watcher perso
 * delle generazioni ancora in corso e scrive i turni mancanti; poi, periodicamente, scrive i turni mancanti delle generazioni
 * terminali (a una distanza {@link #TURN_GRACE} dal completamento, per non incrociare un watcher che sta scrivendo il proprio).
 * Considera le {@link #SWEEP_WINDOW} generazioni terminali di conversazione piu' recenti: un orfano piu' vecchio non viene
 * piu' ripescato (il caso e' gia' un doppio guasto: watcher perso E sweep fermo).
 */
@Service
@ConditionalOnProperty(name = "app.recovery.enabled", havingValue = "true", matchIfMissing = true)
public class ChatRecoveryService {

    /** Un turno di esito piu' recente di cosi' potrebbe essere in scrittura proprio ora da un watcher. */
    private static final Duration TURN_GRACE = Duration.ofMinutes(2);
    private static final int SWEEP_WINDOW = 500;

    private final GenerationService generationService;
    private final ChatMessageRepository chatMessageRepository;
    private final DeepChatGenerationWatcher watcher;
    private final ISystemEvents systemEvents;

    public ChatRecoveryService(GenerationService generationService, ChatMessageRepository chatMessageRepository,
                               DeepChatGenerationWatcher watcher, ISystemEvents systemEvents) {
        this.generationService = generationService;
        this.chatMessageRepository = chatMessageRepository;
        this.watcher = watcher;
        this.systemEvents = systemEvents;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(GenerationRecoveryService.STARTUP_ORDER + 1)
    public void recoverOnStartup() {
        try {
            // Nessuna concorrenza all'avvio (i watcher sono spariti col processo): niente grace period.
            writeMissingChatTurns(Instant.now());
            for (Generation running : generationService.inProgress()) {
                if (running.getConversationId() != null) {
                    watcher.watch(running.getId(), running.getConversationId(), Locale.ITALIAN);
                }
            }
        } catch (RuntimeException e) {
            systemEvents.record(CoreEventSource.INTERNAL, "chatRecovery", e);
        }
    }

    @Scheduled(fixedDelayString = "${app.recovery.sweep-interval:2m}", initialDelayString = "${app.recovery.sweep-interval:2m}")
    public void sweep() {
        try {
            writeMissingChatTurns(Instant.now().minus(TURN_GRACE));
        } catch (RuntimeException e) {
            systemEvents.record(CoreEventSource.INTERNAL, "chatRecoverySweep", e);
        }
    }

    private void writeMissingChatTurns(Instant completedBefore) {
        List<Long> ids = generationService.terminalIdsWithConversation(completedBefore, SWEEP_WINDOW);
        if (ids.isEmpty()) {
            return;
        }
        Set<Long> withTurn = new HashSet<>(chatMessageRepository.findGenerationIdsIn(ids));
        List<Long> missing = ids.stream().filter(id -> !withTurn.contains(id)).toList();
        for (Generation generation : generationService.findAllById(missing)) {
            watcher.persistOutcome(generation, generation.getConversationId());
        }
    }
}
