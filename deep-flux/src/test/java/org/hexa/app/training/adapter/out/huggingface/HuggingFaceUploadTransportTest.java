package org.hexa.app.training.adapter.out.huggingface;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.hexa.app.training.domain.HuggingFaceException;
import org.hexa.app.training.domain.WeightsFile;
import org.hexa.core.kernel.i18n.Messages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Il TRASPORTO vero del caricamento dei pesi, su localhost (nessun servizio reale, nessun costo): un {@code HttpServer} del JDK al posto di HuggingFace e dello
 * storage, e la stessa request factory che Spring Boot sceglie per l'app ({@code ClientHttpRequestFactoryBuilder.detect()}, con i timeout di
 * {@code spring.http.clients.*}). {@code MockRestServiceServer} aggira la factory, quindi non puo' dire se un PUT da centinaia di MB e' in streaming, se ha la
 * Content-Length (un PUT firmato verso lo storage non accetta il chunked) o se il read-timeout interrompe un invio lento.
 */
class HuggingFaceUploadTransportTest {

    private static final int MB = 1024 * 1024;

    private HttpServer server;
    private String origin;
    private final AtomicLong receivedBytes = new AtomicLong(-1);
    private final AtomicReference<String> receivedSha = new AtomicReference<>();
    private final AtomicReference<String> contentLength = new AtomicReference<>();
    private final AtomicReference<String> transferEncoding = new AtomicReference<>();
    /** Byte al secondo che il server accetta (0 = senza limite): un uplink lento, indipendente da quanti byte restituisce ogni read. */
    private volatile long bytesPerSecond;
    private volatile long stallAfterBodyMillis;
    private volatile boolean stopped;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/api/models/sandro/lora/preupload/main", ex -> reply(ex, 200, "{\"files\":[{\"path\":\"flux-lora.safetensors\",\"uploadMode\":\"lfs\",\"shouldIgnore\":false}]}"));
        server.createContext("/sandro/lora.git/info/lfs/objects/batch", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Matcher oid = Pattern.compile("\"oid\"\\s*:\\s*\"([0-9a-f]{64})\"").matcher(body);
            Matcher size = Pattern.compile("\"size\"\\s*:\\s*(\\d+)").matcher(body);
            assertThat(oid.find() && size.find()).isTrue();
            reply(ex, 200, "{\"objects\":[{\"oid\":\"" + oid.group(1) + "\",\"size\":" + size.group(1) + ",\"actions\":{\"upload\":{\"href\":\"" + origin + "/s3/put\"}}}]}");
        });
        server.createContext("/s3/put", this::receivePut);
        server.createContext("/api/models/sandro/lora/commit/main", ex -> {
            ex.getRequestBody().readAllBytes();
            reply(ex, 200, "{}");
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        stopped = true; // i gestori fermi in una pausa escono: nessun thread appeso dopo il test
        server.stop(0);
    }

    private void receivePut(HttpExchange ex) throws IOException {
        contentLength.set(ex.getRequestHeaders().getFirst("Content-Length"));
        transferEncoding.set(ex.getRequestHeaders().getFirst("Transfer-Encoding"));
        try (InputStream in = ex.getRequestBody()) {
            MessageDigest digest = sha256();
            byte[] buffer = new byte[256 * 1024];
            long total = 0;
            long started = System.nanoTime();
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
                total += read;
                if (bytesPerSecond > 0) {
                    pause(total * 1000L / bytesPerSecond - (System.nanoTime() - started) / 1_000_000L); // fino al momento in cui, a quella velocita', questi byte sarebbero arrivati
                }
            }
            receivedBytes.set(total);
            receivedSha.set(HexFormat.of().formatHex(digest.digest()));
            pause(stallAfterBodyMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        ex.sendResponseHeaders(200, -1);
        ex.close();
    }

    private void pause(long millis) throws InterruptedException {
        for (long waited = 0; waited < millis && !stopped; waited += 5) {
            Thread.sleep(5);
        }
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    /** Il client come in produzione: la factory che Boot sceglie dal classpath, col read-timeout di {@code spring.http.clients.read-timeout} (qui piccolo). */
    private HuggingFaceClient client(Duration readTimeout) {
        ClientHttpRequestFactory factory = ClientHttpRequestFactoryBuilder.detect()
                .build(HttpClientSettings.defaults().withTimeouts(Duration.ofSeconds(10), readTimeout));
        factoryName = factory.getClass().getSimpleName();
        return new HuggingFaceClient(RestClient.builder().requestFactory(factory), origin + "/api", messages());
    }

    private static Messages messages() {
        Messages messages = mock(Messages.class);
        when(messages.get(anyString(), any(Object[].class))).thenAnswer(i -> i.getArgument(0));
        when(messages.get(anyString())).thenAnswer(i -> i.getArgument(0));
        return messages;
    }

    private String factoryName;

    // --- il trasporto --------------------------------------------------------------------------------------------

    @Test
    void aLargeUploadIsStreamedWithAContentLengthAndArrivesIntact() throws Exception {
        // Di norma 48 MB; con -Dtransport.size.mb=400 e un heap piccolo (-DargLine=-Xmx128m) diventa la prova che il PUT non bufferizza il file sull'heap.
        long size = Long.getLong("transport.size.mb", 48L) * MB + 12345; // non multiplo di nulla: l'ultimo pezzo e' parziale
        SyntheticWeights weights = new SyntheticWeights(size);
        System.out.println("[transport] upload of " + size / MB + " MB with a max heap of " + Runtime.getRuntime().maxMemory() / MB + " MB");

        client(Duration.ofSeconds(120)).uploadWeights("hf_tok", "sandro/lora", weights, "msg");

        assertThat(receivedBytes.get()).isEqualTo(size);
        assertThat(receivedSha.get()).isEqualTo(weights.sha256());
        assertThat(contentLength.get()).as("lo storage firmato non accetta il chunked").isEqualTo(String.valueOf(size));
        assertThat(transferEncoding.get()).isNull();
    }

    /**
     * Un uplink lento: 20 MB a ~5 MB/s sono 4 secondi, col read-timeout di app a 2. Se il timeout contasse dall'INIZIO dell'invio l'upload fallirebbe a 2 secondi;
     * conta invece dalla FINE dell'invio (Reactor Netty, {@code responseTimeout}: verificato qui, non letto), quindi un LoRA da 170 MB su un collegamento lento
     * riesce anche se l'invio dura piu' di {@code spring.http.clients.read-timeout} (120 s): conta solo l'attesa della risposta dopo l'ultimo byte.
     */
    @Test
    void aSlowUplinkLongerThanTheReadTimeoutStillSucceeds() throws Exception {
        bytesPerSecond = 5L * MB;
        SyntheticWeights weights = new SyntheticWeights(20L * MB);
        long start = System.nanoTime();

        client(Duration.ofSeconds(2)).uploadWeights("hf_tok", "sandro/lora", weights, "msg");

        assertThat(Duration.ofNanos(System.nanoTime() - start)).as("l'invio e' durato piu' del timeout, altrimenti la prova non prova nulla")
                .isGreaterThan(Duration.ofSeconds(3));
        assertThat(receivedBytes.get()).isEqualTo(20L * MB);
        assertThat(factoryName).as("la factory che Boot sceglie con questo classpath (Reactor Netty arriva con Spring AI)").isEqualTo("ReactorClientHttpRequestFactory");
    }

    /** Il timeout resta una protezione: se DOPO aver ricevuto tutto il server non risponde piu', il PUT fallisce al timeout invece di restare appeso. */
    @Test
    void aServerThatNeverAnswersAfterTheBodyFailsAtTheReadTimeout() {
        stallAfterBodyMillis = 30_000;
        SyntheticWeights weights = new SyntheticWeights(2L * MB);
        long start = System.nanoTime();

        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).uploadWeights("hf_tok", "sandro/lora", weights, "msg"))
                .isInstanceOf(HuggingFaceException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(15));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Byte deterministici generati al volo: nessun file, nessun buffer grande, e lo stesso contenuto a ogni lettura. */
    private static final class SyntheticWeights implements WeightsFile {

        private final long size;

        SyntheticWeights(long size) {
            this.size = size;
        }

        @Override
        public String name() {
            return "flux-lora.safetensors";
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public <T> T read(Reader<T> reader) {
            try (InputStream in = stream()) {
                return reader.read(in);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        String sha256() throws IOException {
            MessageDigest digest = HuggingFaceUploadTransportTest.sha256();
            try (InputStream in = stream()) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        }

        private InputStream stream() {
            return new InputStream() {
                private long position;

                @Override
                public int read() {
                    return position >= size ? -1 : (int) ((position++ * 31 + 7) & 0xFF);
                }

                @Override
                public int read(byte[] buffer, int off, int len) {
                    if (position >= size) {
                        return -1;
                    }
                    int n = (int) Math.min(len, size - position);
                    for (int i = 0; i < n; i++) {
                        buffer[off + i] = (byte) ((position++ * 31 + 7) & 0xFF);
                    }
                    return n;
                }
            };
        }
    }
}
