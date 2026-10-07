package org.hexa.core.storage.application;

import org.hexa.core.storage.adapter.out.webdav.WebDavBlobBackend;
import org.hexa.core.storage.port.in.IImageStorageService;
import org.hexa.core.storage.adapter.out.webdav.WebDavBlobBackend;
import org.hexa.core.storage.port.out.IBlobBackend;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** storage.type=webdav sceglie il backend WebDAV (con cache e chiave lette da application.yml/env). */
@SpringBootTest(properties = {
        "storage.type=webdav",
        "storage.webdav.url=http://127.0.0.1:1/dav/",
        "storage.webdav.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "storage.webdav.cache.dir=./target/test-blob-cache",
        "storage.webdav.cache.max-size=1GB"})
class StorageBackendSelectionTest {

    @Autowired
    private IBlobBackend backend;

    @Test
    void webdavTypeSelectsTheWebDavBackend() {
        assertThat(backend).isInstanceOf(WebDavBlobBackend.class);
    }
}
