package org.dual.replicate.service.storage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** Senza storage.type (o con local) il backend e' il filesystem locale. */
@SpringBootTest
class DefaultStorageBackendTest {

    @Autowired
    private IImageStorageService storage;

    @Autowired
    private org.springframework.context.ApplicationContext context;

    @Test
    void defaultIsLocalFileSystem() {
        assertThat(storage).isInstanceOf(LocalFsImageStorageService.class);
    }

    @Test
    void theMigratorIsOffByDefault() {
        assertThat(context.getBeansOfType(LocalToWebDavMigrator.class)).isEmpty();
    }
}
