package org.dual.replicate.app.training.adapter.out.replicate;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.dual.replicate.app.training.domain.WeightsFile;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Il download dei pesi col trasporto vero, su localhost (nessun servizio reale): lo stesso {@code HttpServer} del JDK serve un tar da 200 MB generato al volo, e
 * il gateway lo legge con la factory che Boot sceglie per l'app. Prova due cose che {@code MockRestServiceServer} non puo' provare: che cercare i pesi legge SOLO
 * l'inizio dell'archivio (la chiusura anticipata della risposta non lo scarica tutto) e che la lettura vera arriva intera e identica, in streaming.
 */
class ReplicateWeightsTransportTest {

    private static final int MB = 1024 * 1024;
    private static final long WEIGHTS_SIZE = 200L * MB;

    private HttpServer server;
    private String origin;
    private final AtomicLong served = new AtomicLong();
    private volatile boolean stopped;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/v1/trainings/t1", ex -> {
            byte[] json = ("{\"id\":\"t1\",\"status\":\"succeeded\",\"output\":{\"weights\":\"" + origin + "/tar/flux-lora.tar\"}}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, json.length);
            ex.getResponseBody().write(json);
            ex.close();
        });
        server.createContext("/tar/flux-lora.tar", this::serveTar);
        server.start();
    }

    @AfterEach
    void stopServer() {
        stopped = true;
        server.stop(0);
    }

    private volatile boolean ignoreRange;

    /** Come l'archivio vero (una cartella, poi {@code flux-lora/flux-lora.safetensors}, il contenuto generato al volo), con il Range come il CDN: 206 + Content-Range. */
    private void serveTar(HttpExchange ex) throws IOException {
        long padding = (512 - WEIGHTS_SIZE % 512) % 512;
        long total = 1024 + WEIGHTS_SIZE + padding + 1024;
        String range = ex.getRequestHeaders().getFirst("Range");
        long from = 0;
        long to = total - 1;
        int status = 200;
        if (range != null && !ignoreRange) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("bytes=(\\d+)-(\\d+)").matcher(range);
            assertThat(m.matches()).isTrue();
            from = Long.parseLong(m.group(1));
            to = Math.min(Long.parseLong(m.group(2)), total - 1);
            status = 206;
            ex.getResponseHeaders().add("Content-Range", "bytes " + from + "-" + to + "/" + total);
        }
        ex.getResponseHeaders().add("Content-Type", "application/x-tar");
        ex.sendResponseHeaders(status, to - from + 1);
        byte[] head = new byte[1024];
        System.arraycopy(TarBuilder.directoryHeader("flux-lora/"), 0, head, 0, 512);
        System.arraycopy(TarBuilder.fileHeader("flux-lora/flux-lora.safetensors", WEIGHTS_SIZE), 0, head, 512, 512);
        try (OutputStream out = ex.getResponseBody()) {
            byte[] block = new byte[64 * 1024];
            long position = from;
            while (position <= to && !stopped) {
                int n = (int) Math.min(block.length, to - position + 1);
                for (int i = 0; i < n; i++) {
                    block[i] = byteAt(position + i, head);
                }
                out.write(block, 0, n);
                served.addAndGet(n);
                position += n;
            }
        } catch (IOException e) {
            // il client ha chiuso
        }
    }

    /** Il byte in posizione {@code position} dell'archivio: intestazioni, contenuto (una formula) o zeri di coda. */
    private static byte byteAt(long position, byte[] head) {
        if (position < 1024) {
            return head[(int) position];
        }
        long content = position - 1024;
        return content < WEIGHTS_SIZE ? expectedByte(content) : 0;
    }

    private static byte expectedByte(long contentPosition) {
        return (byte) (contentPosition * 31 + 7);
    }

    private ReplicateTrainerGateway gateway() {
        Messages messages = mock(Messages.class);
        when(messages.get(anyString(), any(Object[].class))).thenAnswer(i -> i.getArgument(0));
        when(messages.get(anyString())).thenAnswer(i -> i.getArgument(0));
        RestClient.Builder builder = RestClient.builder().requestFactory(ClientHttpRequestFactoryBuilder.detect()
                .build(HttpClientSettings.defaults().withTimeouts(Duration.ofSeconds(10), Duration.ofSeconds(30))));
        return new ReplicateTrainerGateway(builder, origin + "/v1", "r8_token", messages, "replicate/fast-flux-trainer", "", "gpu-t4");
    }

    @Test
    void lookingForTheWeightsDownloadsAFewKilobytesNotTheArchive() throws Exception {
        Optional<WeightsFile> weights = gateway().weights("t1");

        assertThat(weights).hasValueSatisfying(w -> {
            assertThat(w.name()).isEqualTo("flux-lora.safetensors");
            assertThat(w.size()).isEqualTo(WEIGHTS_SIZE);
        });
        Thread.sleep(300);
        // Prima dei Range, chiudere la risposta a meta' faceva scaricare comunque tutti i 200 MB (209.717.248 byte serviti): e' il motivo dei Range.
        assertThat(served.get()).as("byte spediti dal server per trovare i pesi").isLessThanOrEqualTo(2L * 512);
    }

    @Test
    void readingTheWeightsStreamsExactlyTheFileInsideTheArchive() throws Exception {
        WeightsFile weights = gateway().weights("t1").orElseThrow();
        served.set(0);

        String sha = weights.read(in -> sha256Of(in));

        MessageDigest expected = MessageDigest.getInstance("SHA-256");
        byte[] block = new byte[64 * 1024];
        for (long position = 0; position < WEIGHTS_SIZE; position += block.length) {
            int n = (int) Math.min(block.length, WEIGHTS_SIZE - position);
            for (int i = 0; i < n; i++) {
                block[i] = expectedByte(position + i);
            }
            expected.update(block, 0, n);
        }
        assertThat(sha).isEqualTo(HexFormat.of().formatHex(expected.digest()));
        assertThat(served.get()).as("solo i byte del file: nessuna intestazione ne' padding dell'archivio").isEqualTo(WEIGHTS_SIZE);
    }

    /** Un server che ignora il Range manderebbe l'archivio dal primo byte: i pesi sarebbero corrotti, quindi e' un errore chiaro. */
    @Test
    void aServerThatIgnoresTheRangeIsRefusedInsteadOfCorruptingTheWeights() {
        ignoreRange = true;

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> gateway().weights("t1"))
                .isInstanceOf(org.dual.replicate.app.generation.domain.ReplicateException.class).hasMessageContaining("replicate.error.rangeUnsupported");
    }

    private static String sha256Of(InputStream in) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
                total += read;
            }
            assertThat(total).isEqualTo(WEIGHTS_SIZE);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
