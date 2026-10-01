package org.dual.replicate.core.storage.application;

import org.dual.replicate.core.storage.adapter.out.local.LocalFsBlobBackend;
import org.dual.replicate.core.storage.port.in.IImageStorageService;
import org.dual.replicate.core.storage.adapter.out.local.LocalFsBlobBackend;
import org.dual.replicate.core.storage.port.out.IBlobBackend;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** Senza storage.type (o con local) il backend e' il filesystem locale. */
@SpringBootTest
class DefaultStorageBackendTest {

    @Autowired
    private IBlobBackend backend;

    @Autowired
    private org.springframework.context.ApplicationContext context;

    @Test
    void defaultIsLocalFileSystem() {
        assertThat(backend).isInstanceOf(LocalFsBlobBackend.class);
    }

    @Test
    void theMigratorIsOffByDefault() {
        assertThat(context.getBeansOfType(LocalToWebDavMigrator.class)).isEmpty();
    }
}
