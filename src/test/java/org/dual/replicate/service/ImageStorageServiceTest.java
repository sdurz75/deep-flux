package org.dual.replicate.service;

import java.io.IOException;
import java.io.UncheckedIOException;
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
class ImageStorageServiceTest {

    @TempDir
    Path dir;

    private HttpServer server;
    private ImageStorageService service;

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
        server.start();
        Messages messages = mock(Messages.class);
        when(messages.get(anyString(), any(Object[].class))).thenReturn("errore");
        service = new ImageStorageService(dir.toString(), messages);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    @Test
    void downloadAndStoreStreamsTheFileUnderGenerationIdAndIndexKeepingTheExtension() throws IOException {
        String filename = service.downloadAndStore(12L, 0, url("/x.mp4"));

        assertThat(filename).isEqualTo("12-0.mp4");
        assertThat(Files.readString(dir.resolve("12-0.mp4"))).isEqualTo("video-bytes");
    }

    @Test
    void downloadAndStoreFailsOnHttpErrorWithoutLeavingAFile() {
        assertThatThrownBy(() -> service.downloadAndStore(13L, 0, url("/missing.mp4")))
                .isInstanceOf(UncheckedIOException.class);
        assertThat(dir.resolve("13-0.mp4")).doesNotExist();
    }

    @Test
    void readAsDataUriUsesTheMimeTypeOfTheExtension() throws IOException {
        Files.writeString(dir.resolve("1-0.png"), "abc");
        Files.writeString(dir.resolve("2-0.jpg"), "abc");

        assertThat(service.readAsDataUri("1-0.png")).isEqualTo("data:image/png;base64,YWJj");
        assertThat(service.readAsDataUri("2-0.jpg")).isEqualTo("data:image/jpeg;base64,YWJj");
    }

    @Test
    void readAsDataUriRejectsPathTraversalAndMissingFiles() {
        assertThatThrownBy(() -> service.readAsDataUri("../secret.png")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.readAsDataUri("nope.png")).isInstanceOf(UncheckedIOException.class);
    }
}
