package org.hexa.core.storage.application;

import org.hexa.core.storage.adapter.in.scheduling.LocalToWebDavMigrationStarter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/** Il migratore esiste solo se abilitato (e con type=webdav); qui su una cartella vuota, nessuna richiesta di rete. */
@SpringBootTest(properties = {
        "storage.type=webdav",
        "storage.webdav.url=http://127.0.0.1:1/dav/",
        "storage.webdav.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "storage.webdav.cache.dir=./target/test-migration-cache",
        "storage.images-dir=./target/test-migration-empty",
        "storage.migration.from-local.enabled=true"})
class LocalToWebDavMigratorWiringTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void migratorAndItsStarterAreRegisteredWhenEnabled() {
        assertThat(context.getBeansOfType(LocalToWebDavMigrator.class)).hasSize(1);
        assertThat(context.getBeansOfType(LocalToWebDavMigrationStarter.class)).hasSize(1);
    }
}
