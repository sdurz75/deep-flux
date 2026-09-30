package org.dual.replicate.service.storage;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.sun.net.httpserver.HttpServer;
import org.dual.replicate.i18n.Messages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Nessuna chiamata a Replicate: un HttpServer JDK locale serve i byte da scaricare. */
class LocalFsImageStorageServiceTest {

    @TempDir
    Path dir;

    private HttpServer server;
    private LocalFsImageStorageService service;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/x.mp4", exchange -> {
            byte[] body = "video-bytes".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/missing.mp4", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        java.util.concurrent.atomic.AtomicInteger flakyCalls = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/flaky.mp4", exchange -> {
            if (flakyCalls.incrementAndGet() == 1) {
                exchange.sendResponseHeaders(503, -1);
            } else {
                byte[] body = "video-bytes".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        Messages messages = mock(Messages.class);
        when(messages.get(anyString(), any(Object[].class))).thenReturn("errore");
        service = new LocalFsImageStorageService(dir.toString(), messages);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    @Test
    void downloadAndStoreStreamsTheFileUnderARandomHashNameKeepingTheExtension() throws IOException {
        String filename = service.downloadAndStore(url("/x.mp4"));

        assertThat(filename).matches("[0-9a-f]{64}\\.mp4");
        assertThat(Files.readString(dir.resolve(AbstractImageStorageService.shardPath(filename)))).isEqualTo("video-bytes");
    }

    /** Un blip transitorio non deve far fallire una prediction riuscita (gli URL di output scadono); un 404 invece no. */
    @Test
    void aTransientDownloadErrorIsRetried() throws IOException {
        String filename = service.downloadAndStore(url("/flaky.mp4"));

        assertThat(Files.readString(dir.resolve(AbstractImageStorageService.shardPath(filename)))).isEqualTo("video-bytes");
    }

    @Test
    void everyDownloadGetsADistinctName() {
        assertThat(service.downloadAndStore(url("/x.mp4"))).isNotEqualTo(service.downloadAndStore(url("/x.mp4")));
    }

    @Test
    void downloadAndStoreFailsOnHttpErrorWithoutLeavingAFile() throws IOException {
        assertThatThrownBy(() -> service.downloadAndStore(url("/missing.mp4")))
                .isInstanceOf(StorageException.class);
        try (var files = Files.walk(dir)) {
            assertThat(files.filter(Files::isRegularFile)).isEmpty();
        }
    }

    @Test
    void readAsDataUriUsesTheMimeTypeOfTheExtension() throws IOException {
        writeSharded("1-0.png");
        writeSharded("2-0.jpg");

        assertThat(service.readAsDataUri("1-0.png")).isEqualTo("data:image/png;base64,YWJj");
        assertThat(service.readAsDataUri("2-0.jpg")).isEqualTo("data:image/jpeg;base64,YWJj");
    }

    @Test
    void readAsDataUriRejectsPathTraversalAndMissingFiles() {
        assertThatThrownBy(() -> service.readAsDataUri("../secret.png")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.readAsDataUri("nope.png")).isInstanceOf(StorageException.class);
    }

    @Test
    void storeUploadSavesUnderGeneratedNameDetectingTypeFromMagicBytes() throws IOException {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1};
        var upload = new org.springframework.mock.web.MockMultipartFile("sourceUpload", "../../evil.jpg", "image/jpeg", png);

        String filename = service.storeUpload(upload);

        assertThat(filename).matches("[0-9a-f]{64}\\.png").doesNotContain("evil");
        assertThat(Files.readAllBytes(dir.resolve(AbstractImageStorageService.shardPath(filename)))).isEqualTo(png);
    }

    @Test
    void storeUploadRejectsNonImagesEvenWithAnImageContentType() {
        var upload = new org.springframework.mock.web.MockMultipartFile("sourceUpload", "a.png", "image/png", "not an image".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.storeUpload(upload)).isInstanceOf(StorageException.class);
        assertThat(dir.toFile().list()).isEmpty();
    }

    private void writeSharded(String filename) throws IOException {
        Path file = dir.resolve(AbstractImageStorageService.shardPath(filename));
        Files.createDirectories(file.getParent());
        Files.writeString(file, "abc");
    }

    @Test
    void filesAreNestedByHashOfTheFilenameNotFlat() throws IOException {
        new LocalFsImageStorageService(dir.toString(), org.mockito.Mockito.mock(org.dual.replicate.i18n.Messages.class));
        String path = AbstractImageStorageService.shardPath("12-0.png");
        assertThat(path).matches("[0-9a-f]{2}/[0-9a-f]{2}/12-0\\.png");
        assertThat(AbstractImageStorageService.shardPath("12-0.png")).isEqualTo(path);
    }
}
