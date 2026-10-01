package org.dual.replicate.service.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import org.dual.replicate.core.events.domain.CoreEventSource;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.dual.replicate.service.SystemEventService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Un server WebDAV in memoria (HttpServer JDK: PUT/GET con Range/HEAD/DELETE/MKCOL/MOVE) esercita il client vero.
 * Nessuna chiamata di rete esterna.
 */
class WebDavImageStorageServiceTest {

    private static final byte[] PNG_HEAD = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    @TempDir
    Path tmp;

    private FakeWebDavServer dav;
    private Map<String, byte[]> store;
    private AtomicInteger mkcols;
    private AtomicBoolean down;
    private final String key = Base64.getEncoder().encodeToString(random(32, 7));
    private Messages messages;
    private SystemEventService systemEvents;
    private byte[] png;

    private static byte[] random(int length, long seed) {
        byte[] b = new byte[length];
        new Random(seed).nextBytes(b);
        return b;
    }

    @BeforeEach
    void setUp() throws IOException {
        png = new byte[PNG_HEAD.length + 200_000];
        System.arraycopy(PNG_HEAD, 0, png, 0, PNG_HEAD.length);
        System.arraycopy(random(200_000, 3), 0, png, PNG_HEAD.length, 200_000);
        Arrays.fill(png, PNG_HEAD.length, PNG_HEAD.length + 64, (byte) 'X'); // stringa riconoscibile nel chiaro

        dav = new FakeWebDavServer();
        dav.serve("/src/x.png", png);
        store = dav.store;
        mkcols = dav.mkcols;
        down = dav.down;
        messages = mock(Messages.class);
        when(messages.get(anyString(), any(Object[].class))).thenReturn("errore");
        systemEvents = mock(SystemEventService.class);
    }

    @AfterEach
    void tearDown() {
        dav.stop();
    }

    private String base() {
        return dav.base();
    }

    private WebDavImageStorageService service(Path cacheDir, DataSize cacheMax) throws IOException {
        return new WebDavImageStorageService(base() + "/dav/", "user", "secret", key, cacheDir.toString(), cacheMax,
                messages, RestClient.builder(), systemEvents);
    }

    private WebDavImageStorageService service() throws IOException {
        return service(tmp.resolve("cache"), DataSize.ofMegabytes(10));
    }

    private int getsOf(String name) {
        return dav.getsOf(AbstractImageStorageService.shardPath(name));
    }

    // --- test -------------------------------------------------------------------------------------------------

    @Test
    void storesOnlyEncryptedBytesOnTheServerAndReadsThemBack() throws IOException {
        WebDavImageStorageService service = service();

        String filename = service.downloadAndStore(base() + "/src/x.png");

        assertThat(filename).matches("[0-9a-f]{64}\\.png");
        assertThat(store).containsOnlyKeys("/dav/"+AbstractImageStorageService.shardPath(filename)); // niente .part rimasto
        byte[] remote = store.get("/dav/"+AbstractImageStorageService.shardPath(filename));
        assertThat(new String(remote, StandardCharsets.ISO_8859_1)).doesNotContain("XXXXXXXX");
        assertThat(Arrays.copyOf(remote, PNG_HEAD.length)).isNotEqualTo(PNG_HEAD);
        assertThat(service.read(filename).bytes()).isEqualTo(png);
        assertThat(service.read(filename).mimeType()).isEqualTo("image/png");
        assertThat(service.size(filename)).hasValue(png.length);
    }

    @Test
    void writeThroughCacheAvoidsAnyRoundTripOnReads() throws IOException {
        WebDavImageStorageService service = service();
        String filename = service.downloadAndStore(base() + "/src/x.png");

        service.read(filename);
        service.read(filename);
        service.size(filename);

        assertThat(getsOf(filename)).isZero();
    }

    @Test
    void aCacheMissDownloadsTheBlobOnceThenServesFromCache() throws IOException {
        String filename = service().downloadAndStore(base() + "/src/x.png");
        WebDavImageStorageService fresh = service(tmp.resolve("other-cache"), DataSize.ofMegabytes(10));

        assertThat(fresh.read(filename).bytes()).isEqualTo(png);
        assertThat(fresh.read(filename).bytes()).isEqualTo(png);
        assertThat(fresh.size(filename)).hasValue(png.length);

        assertThat(getsOf(filename)).isEqualTo(1);
    }

    @Test
    void aVideoCacheMissIsServedByRangeAndWarmsTheCacheInBackground() throws IOException {
        byte[] mp4 = random(300_000, 11);
        Path source = tmp.resolve("clip.mp4");
        java.nio.file.Files.write(source, mp4);
        service().importFile("12-0.mp4", source);
        WebDavImageStorageService fresh = service(tmp.resolve("other-cache"), DataSize.ofMegabytes(10));

        try (InputStream in = fresh.openRange("12-0.mp4", 0, 1000)) {
            assertThat(in.readAllBytes()).isEqualTo(Arrays.copyOfRange(mp4, 0, 1000));
        }
        // la richiesta non e' stata bloccata dal download intero: e' un GET a range; poi la cache si scalda da sola
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(
                () -> assertThat(tmp.resolve("other-cache").resolve("12-0.mp4")).exists());
        down.set(true);
        try (InputStream in = fresh.openRange("12-0.mp4", 250_000, 500)) {
            assertThat(in.readAllBytes()).isEqualTo(Arrays.copyOfRange(mp4, 250_000, 250_500));
        }
    }

    @Test
    void cachedBlobsAreStillServedWhenWebDavIsDown() throws IOException {
        WebDavImageStorageService service = service();
        String filename = service.downloadAndStore(base() + "/src/x.png");
        down.set(true);

        assertThat(service.read(filename).bytes()).isEqualTo(png);
    }

    @Test
    void withoutCacheRangesAreReadFromTheServer() throws IOException {
        WebDavImageStorageService service = service(tmp.resolve("no-cache"), DataSize.ofBytes(0));
        String filename = service.downloadAndStore(base() + "/src/x.png");

        try (InputStream in = service.openRange(filename, 100_000, 500)) {
            assertThat(in.readAllBytes()).isEqualTo(Arrays.copyOfRange(png, 100_000, 100_500));
        }
        assertThat(getsOf(filename)).isGreaterThan(0);
    }

    @Test
    void aBlobLargerThanTheCacheLimitIsServedByRangeFromTheServer() throws IOException {
        WebDavImageStorageService service = service(tmp.resolve("tiny-cache"), DataSize.ofKilobytes(1));
        String filename = service.downloadAndStore(base() + "/src/x.png");

        assertThat(service.read(filename).bytes()).isEqualTo(png);
        assertThat(tmp.resolve("tiny-cache").toFile().list()).isEmpty();
    }

    @Test
    void uploadIsEncryptedToo() throws IOException {
        WebDavImageStorageService service = service();
        var upload = new org.springframework.mock.web.MockMultipartFile("sourceUpload", "a.png", "image/png", png);

        String filename = service.storeUpload(upload);

        assertThat(filename).matches("[0-9a-f]{64}\\.png");
        assertThat(store.get("/dav/" + AbstractImageStorageService.shardPath(filename))).isNotEqualTo(png);
        assertThat(service.read(filename).bytes()).isEqualTo(png);
    }

    @Test
    void deleteRemovesFromServerAndCache() throws IOException {
        WebDavImageStorageService service = service();
        String filename = service.downloadAndStore(base() + "/src/x.png");

        service.delete(filename);

        assertThat(store).isEmpty();
        assertThat(service.size(filename)).isEmpty();
        service.delete(filename); // gia' assente: nessun errore
        service.delete(null);
    }

    @Test
    void missingFileHasNoSizeAndReadFails() throws IOException {
        WebDavImageStorageService service = service();

        assertThat(service.size("nope.png")).isEmpty();
        assertThatThrownBy(() -> service.read("nope.png")).isInstanceOf(StorageException.class);
    }

    @Test
    void theCollectionIsCreatedOnlyOnce() throws IOException {
        WebDavImageStorageService service = service();

        service.downloadAndStore(base() + "/src/x.png");
        service.downloadAndStore(base() + "/src/x.png");

        assertThat(mkcols.get()).isBetween(3, 5); // base + shard (i due nomi casuali possono avere shard diversi), mai ripetute
    }

    @Test
    void aRejectedMkcolOnAnExistingCollectionDoesNotBlockWrites() throws IOException {
        dav.mkcolStatus = 409; // es. Yandex sulla radice del disco
        WebDavImageStorageService service = service();

        String filename = service.downloadAndStore(base() + "/src/x.png");

        assertThat(store).containsKey("/dav/" + AbstractImageStorageService.shardPath(filename));
        assertThat(service.read(filename).bytes()).isEqualTo(png);
    }

    @Test
    void aTransientServerErrorOnWriteIsRetriedAndTheSaveSucceeds() throws IOException {
        WebDavImageStorageService service = service();
        dav.failMethod = "PUT";
        dav.failuresLeft.set(2); // 503 sui primi due tentativi di PUT, il terzo (ultimo ritentativo) riesce

        String filename = service.downloadAndStore(base() + "/src/x.png");

        assertThat(store).containsOnlyKeys("/dav/" + AbstractImageStorageService.shardPath(filename));
        assertThat(service.read(filename).bytes()).isEqualTo(png);
        assertThat(dav.requestsOf("PUT")).isEqualTo(3);
    }

    @Test
    void aPermanentErrorIsNotRetried() throws IOException {
        WebDavImageStorageService service = service();
        service.downloadAndStore(base() + "/src/x.png"); // crea le collezioni
        int puts = dav.requestsOf("PUT");
        dav.failureStatus = 403;
        dav.failuresLeft.set(100);

        assertThatThrownBy(() -> service.storeUpload(new org.springframework.mock.web.MockMultipartFile("sourceUpload", "a.png", "image/png", png))).isNotNull();

        assertThat(dav.requestsOf("PUT")).isEqualTo(puts + 1);
    }

    @Test
    void retriesAreBoundedThenTheTransientErrorSurfaces() throws IOException {
        WebDavImageStorageService service = service();
        service.downloadAndStore(base() + "/src/x.png");
        int puts = dav.requestsOf("PUT");
        dav.failuresLeft.set(100);

        assertThatThrownBy(() -> service.downloadAndStore(base() + "/src/x.png"))
                .isInstanceOf(StorageException.class);

        // 3 tentativi WebDAV per ognuno dei 3 tentativi di download (retry nidificati, accettato: vedi AbstractImageStorageService)
        assertThat(dav.requestsOf("PUT")).isEqualTo(puts + 9);
        assertThat(store).hasSize(1); // niente .part rimasto
    }

    @Test
    void aFailedPartCleanupIsRecordedNotSwallowed() throws IOException {
        WebDavImageStorageService service = service();
        service.downloadAndStore(base() + "/src/x.png");
        dav.failureStatus = 403; // permanente: la PUT fallisce e anche il DELETE del .part
        dav.failuresLeft.set(100);

        assertThatThrownBy(() -> service.downloadAndStore(base() + "/src/x.png")).isNotNull();

        org.mockito.Mockito.verify(systemEvents).record(org.mockito.ArgumentMatchers.eq(org.dual.replicate.core.events.domain.CoreEventSource.STORAGE),
                org.mockito.ArgumentMatchers.eq("cleanupPart"), any(Throwable.class));
    }

    @Test
    void deletingAMissingFileIsNotAnError() throws IOException {
        WebDavImageStorageService service = service();
        service.downloadAndStore(base() + "/src/x.png");

        service.delete("nonexistent.png"); // 404 sul server: tollerato

        org.mockito.Mockito.verify(systemEvents, org.mockito.Mockito.never()).record(any(), anyString(), any(Throwable.class));
    }

    @Test
    void aFailingServerFailsTheSaveWithoutLeavingTempFiles() throws IOException {
        WebDavImageStorageService service = service();
        down.set(true);

        assertThatThrownBy(() -> service.downloadAndStore(base() + "/src/x.png"))
                .isInstanceOf(StorageException.class);

        assertThat(store).isEmpty();
        assertThat(tmp.resolve("cache").toFile().list()).isEmpty();
    }

    @Test
    void rejectsPathTraversal() throws IOException {
        WebDavImageStorageService service = service();

        assertThatThrownBy(() -> service.read("../x.png")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.delete("a/b.png")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesToStartWithoutUrlOrWithAnInvalidKey() {
        assertThatThrownBy(() -> new WebDavImageStorageService("", "", "", key, tmp.toString(), DataSize.ofMegabytes(1),
                messages, RestClient.builder(), systemEvents)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new WebDavImageStorageService(base(), "", "", "", tmp.toString(),
                DataSize.ofMegabytes(1), messages, RestClient.builder(), systemEvents))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebDavImageStorageService(base(), "", "",
                Base64.getEncoder().encodeToString(new byte[16]), tmp.toString(), DataSize.ofMegabytes(1), messages,
                RestClient.builder(), systemEvents)).isInstanceOf(IllegalArgumentException.class);
    }
}
