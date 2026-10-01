package org.dual.replicate.app.search.adapter.in.scheduling;

import org.dual.replicate.app.search.port.in.IArchiveIndex;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Avvia la riconciliazione dell'indice: all'avvio (backfill) e ogni {@code app.search.reindex-interval}. */
@Component
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "true", matchIfMissing = true)
class ArchiveIndexScheduler {

    private final IArchiveIndex index;

    ArchiveIndexScheduler(IArchiveIndex index) {
        this.index = index;
    }

    @EventListener(ApplicationReadyEvent.class)
    void reindexOnStartup() {
        index.reindexAsync();
    }

    @Scheduled(fixedDelayString = "${app.search.reindex-interval:5m}", initialDelayString = "${app.search.reindex-interval:5m}")
    void sweep() {
        index.reindexAsync();
    }
}
