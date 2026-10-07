package org.hexa.core.storage.adapter.in.scheduling;

import org.hexa.core.storage.port.in.IBlobMigration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Fa partire la migrazione locale -> WebDAV all'avvio, solo se abilitata ({@code storage.migration.from-local.enabled=true}). */
@Component
@ConditionalOnProperty(name = "storage.migration.from-local.enabled", havingValue = "true")
public class LocalToWebDavMigrationStarter {

    private final IBlobMigration migration;

    public LocalToWebDavMigrationStarter(IBlobMigration migration) {
        this.migration = migration;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        migration.migrate();
    }
}
