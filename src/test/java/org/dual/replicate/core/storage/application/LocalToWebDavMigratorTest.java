package org.dual.replicate.core.storage.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Random;

import org.dual.replicate.core.kernel.i18n.Messages;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.dual.replicate.core.storage.adapter.out.http.HttpFileFetcher;
import org.dual.replicate.core.storage.adapter.out.local.LocalFsBlobBackend;
import org.dual.replicate.core.storage.adapter.out.webdav.FakeWebDavServer;
import org.dual.replicate.core.storage.adapter.out.webdav.WebDavBlobBackend;
import org.dual.replicate.core.storage.domain.StorageNames;
import org.dual.replicate.core.storage.port.out.IBlobImportTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Migrazione locale -> WebDAV contro un WebDAV in memoria: nessuna rete esterna. */
class LocalToWebDavMigratorTest {

    @TempDir
    Path tmp;

    private FakeWebDavServer dav;
    private WebDavBlobBackend webdav;
    private ImageStorageService storage;
    private ISystemEvents systemEvents;
    private Path images;
    private final byte[] photo = new byte[150_000];

    @BeforeEach
    void setUp() throws IOException {
        new Random(5).nextBytes(photo);
        dav = new FakeWebDavServer();
        Messages messages = mock(Messages.class);
        when(messages.get(anyString(), any(Object[].class))).thenReturn("errore");
        systemEvents = mock(ISystemEvents.class);
        byte[] keyBytes = new byte[32];
        new Random(9).nextBytes(keyBytes);
        String key = Base64.getEncoder().encodeToString(keyBytes);
        webdav = new WebDavBlobBackend(dav.base() + "/dav/", "user", "secret", key,
                tmp.resolve("cache").toString(), DataSize.ofMegabytes(10), messages, RestClient.builder(), systemEvents);
        storage = new ImageStorageService(webdav, new HttpFileFetcher(messages, RestClient.builder()), messages);
        images = Files.createDirectories(tmp.resolve("images"));
        Files.write(images.resolve("1-0.png"), photo);
        Files.write(images.resolve("2-0.mp4"), "video".getBytes());
        // uno annidato come li scrive LocalFsBlobBackend: il migratore cammina ricorsivamente
        Path nested = images.resolve(StorageNames.shardPath("upload-abc.jpg"));
        Files.createDirectories(nested.getParent());
        Files.write(nested, "jpg".getBytes());
        Files.write(images.resolve("9-0.png.part"), "partial".getBytes()); // temporaneo: da ignorare
    }

    @AfterEach
    void tearDown() {
        dav.stop();
    }

    private LocalToWebDavMigrator migrator(boolean deleteLocal) {
        @SuppressWarnings("unchecked")
        ObjectProvider<IBlobImportTarget> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(webdav);
        return new LocalToWebDavMigrator(provider, systemEvents, images.toString(), deleteLocal);
    }

    @Test
    void migratesEveryFileEncryptedAndKeepsTheLocalOnes() throws IOException {
        var result = migrator(false).migrate();

        assertThat(result).isEqualTo(new LocalToWebDavMigrator.Result(3, 0, 0, 0, photo.length + 5 + 3));
        assertThat(dav.store).containsOnlyKeys(dav("1-0.png"), dav("2-0.mp4"), dav("upload-abc.jpg"));
        assertThat(dav.store.get(dav("1-0.png"))).isNotEqualTo(photo);
        assertThat(storage.read("1-0.png").bytes()).isEqualTo(photo);
        assertThat(images.resolve("1-0.png")).exists();
        assertThat(images.resolve("9-0.png.part")).exists();
    }

    @Test
    void isIdempotentAndSkipsFilesAlreadyOnTheServer() throws IOException {
        migrator(false).migrate();
        int putsAfterFirstRun = dav.store.size();

        var second = migrator(false).migrate();

        assertThat(second).isEqualTo(new LocalToWebDavMigrator.Result(0, 3, 0, 0, 0));
        assertThat(dav.store).hasSize(putsAfterFirstRun);
    }

    @Test
    void resumesAfterAPartialRun() throws IOException {
        webdav.importFile("1-0.png", images.resolve("1-0.png")); // gia' migrato in un giro interrotto

        var result = migrator(false).migrate();

        assertThat(result.migrated()).isEqualTo(2);
        assertThat(result.skipped()).isEqualTo(1);
    }

    @Test
    void deleteLocalRemovesOnlyVerifiedFiles() throws IOException {
        var result = migrator(true).migrate();

        assertThat(result.deletedLocal()).isEqualTo(3);
        assertThat(images.resolve("1-0.png")).doesNotExist();
        assertThat(images.resolve("2-0.mp4")).doesNotExist();
        assertThat(images.resolve("upload-abc.jpg")).doesNotExist();
        assertThat(images.resolve("9-0.png.part")).exists(); // mai toccati i temporanei
        assertThat(storage.read("1-0.png").bytes()).isEqualTo(photo);
    }

    @Test
    void deleteLocalKeepsAFileWhoseServerSizeDiffers() throws IOException {
        migrator(false).migrate();
        // Il server ha una copia diversa (dimensione in chiaro diversa) di un file locale.
        Files.write(images.resolve("1-0.png"), new byte[photo.length + 10]);

        var result = migrator(true).migrate();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(images.resolve("1-0.png")).exists();
        verify(systemEvents).record(any(), eq("migrateLocalToWebDav"), any(Throwable.class));
    }

    @Test
    void aFailureOnOneFileDoesNotStopTheOthers() throws IOException {
        dav.down.set(true);

        var result = migrator(true).migrate();

        assertThat(result.migrated()).isZero();
        assertThat(result.failed()).isEqualTo(3);
        assertThat(images.resolve("1-0.png")).exists(); // niente perso con il server giu'
        verify(systemEvents, times(3)).record(any(), eq("migrateLocalToWebDav"), any(Throwable.class));
    }

    @Test
    void aMissingSourceDirectoryIsANoOp() {
        LocalToWebDavMigrator migrator = migratorFor(tmp.resolve("does-not-exist"));

        assertThat(migrator.migrate()).isEqualTo(new LocalToWebDavMigrator.Result(0, 0, 0, 0, 0));
        verify(systemEvents, never()).record(any(), anyString(), any(Throwable.class));
    }

    @Test
    void requiresTheWebDavBackend() {
        @SuppressWarnings("unchecked")
        ObjectProvider<IBlobImportTarget> none = mock(ObjectProvider.class);
        when(none.getIfAvailable()).thenReturn(null);

        assertThatThrownBy(() -> new LocalToWebDavMigrator(none, systemEvents, images.toString(), false))
                .isInstanceOf(IllegalStateException.class);
    }

    private LocalToWebDavMigrator migratorFor(Path dir) {
        @SuppressWarnings("unchecked")
        ObjectProvider<IBlobImportTarget> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(webdav);
        return new LocalToWebDavMigrator(provider, systemEvents, dir.toString(), false);
    }

    private static String dav(String filename) {
        return "/dav/" + StorageNames.shardPath(filename);
    }
}
