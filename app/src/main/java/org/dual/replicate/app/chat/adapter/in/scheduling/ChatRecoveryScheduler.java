package org.dual.replicate.app.chat.adapter.in.scheduling;

import org.dual.replicate.app.AppStartupOrder;
import org.dual.replicate.app.chat.port.in.IChatRecovery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Avvia il recupero della chat: all'avvio (dopo che {@code GenerationRecoveryService} ha fatto avanzare le righe in corso, vedi
 * {@link AppStartupOrder}) e ogni {@code app.recovery.sweep-interval}. Disattivabile con {@code app.recovery.enabled=false} (i test).
 */
@Component
@ConditionalOnProperty(name = "app.recovery.enabled", havingValue = "true", matchIfMissing = true)
class ChatRecoveryScheduler {

    private final IChatRecovery recovery;

    ChatRecoveryScheduler(IChatRecovery recovery) {
        this.recovery = recovery;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(AppStartupOrder.CHAT_RECOVERY)
    void recoverOnStartup() {
        recovery.recoverOnStartup();
    }

    @Scheduled(fixedDelayString = "${app.recovery.sweep-interval:2m}", initialDelayString = "${app.recovery.sweep-interval:2m}")
    void sweep() {
        recovery.sweep();
    }
}
