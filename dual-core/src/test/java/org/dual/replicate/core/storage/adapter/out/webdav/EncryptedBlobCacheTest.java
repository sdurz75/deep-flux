package org.dual.replicate.core.storage.adapter.out.webdav;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class EncryptedBlobCacheTest {

    @TempDir
    Path dir;

    private Path blob(EncryptedBlobCache cache, int size) throws IOException {
        Path temp = cache.newTempFile();
        Files.write(temp, new byte[size]);
        return temp;
    }

    @Test
    void adoptedBlobIsServedFromCache() throws IOException {
        EncryptedBlobCache cache = new EncryptedBlobCache(dir, 100);

        cache.adopt("a.png", blob(cache, 10));

        assertThat(cache.get("a.png")).isPresent();
        assertThat(cache.get("b.png")).isEmpty();
        assertThat(cache.totalBytes()).isEqualTo(10);
    }

    @Test
    void evictsLeastRecentlyUsedWhenOverTheLimit() throws IOException {
        EncryptedBlobCache cache = new EncryptedBlobCache(dir, 25);
        cache.adopt("a.png", blob(cache, 10));
        cache.adopt("b.png", blob(cache, 10));
        cache.get("a.png"); // a diventa il piu' recente: b e' il meno usato

        cache.adopt("c.png", blob(cache, 10));

        assertThat(cache.get("b.png")).isEmpty();
        assertThat(cache.get("a.png")).isPresent();
        assertThat(cache.get("c.png")).isPresent();
        assertThat(cache.totalBytes()).isEqualTo(20);
        assertThat(dir.resolve("b.png")).doesNotExist();
    }

    @Test
    void aBlobLargerThanTheLimitIsNotCached() throws IOException {
        EncryptedBlobCache cache = new EncryptedBlobCache(dir, 10);

        cache.adopt("big.mp4", blob(cache, 11));

        assertThat(cache.get("big.mp4")).isEmpty();
        assertThat(dir.toFile().list()).isEmpty();
    }

    @Test
    void zeroMaxSizeDisablesTheCache() throws IOException {
        EncryptedBlobCache cache = new EncryptedBlobCache(dir, 0);

        cache.adopt("a.png", blob(cache, 1));

        assertThat(cache.enabled()).isFalse();
        assertThat(cache.get("a.png")).isEmpty();
        assertThat(dir.toFile().list()).isEmpty();
    }

    @Test
    void removeDeletesTheBlob() throws IOException {
        EncryptedBlobCache cache = new EncryptedBlobCache(dir, 100);
        cache.adopt("a.png", blob(cache, 10));

        cache.remove("a.png");

        assertThat(cache.get("a.png")).isEmpty();
        assertThat(cache.totalBytes()).isZero();
        assertThat(dir.resolve("a.png")).doesNotExist();
    }

    @Test
    void indexIsRebuiltOnStartupInLruOrderAndOrphanTempFilesAreRemoved() throws IOException {
        Files.write(dir.resolve("old.png"), new byte[10]);
        Files.write(dir.resolve("new.png"), new byte[10]);
        Files.write(dir.resolve("blob-orphan.part"), new byte[10]);
        Files.setLastModifiedTime(dir.resolve("old.png"), FileTime.from(Instant.now().minusSeconds(100)));
        Files.setLastModifiedTime(dir.resolve("new.png"), FileTime.from(Instant.now()));

        EncryptedBlobCache cache = new EncryptedBlobCache(dir, 25);

        assertThat(dir.resolve("blob-orphan.part")).doesNotExist();
        assertThat(cache.totalBytes()).isEqualTo(20);
        cache.adopt("c.png", blob(cache, 10));
        assertThat(cache.get("old.png")).isEmpty(); // il piu' vecchio per data di modifica esce per primo
        assertThat(cache.get("new.png")).isPresent();
    }

    @Test
    void startupEvictsWhenTheDirectoryExceedsALoweredLimit() throws IOException {
        Files.write(dir.resolve("a.png"), new byte[10]);
        Files.write(dir.resolve("b.png"), new byte[10]);

        EncryptedBlobCache cache = new EncryptedBlobCache(dir, 12);

        assertThat(cache.totalBytes()).isLessThanOrEqualTo(12);
    }

    @Test
    void getOrLoadDownloadsOnceForConcurrentRequests() throws Exception {
        EncryptedBlobCache cache = new EncryptedBlobCache(dir, 100);
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<Path>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 6; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return cache.getOrLoad("a.png", target -> {
                        loads.incrementAndGet();
                        java.util.concurrent.locks.LockSupport.parkNanos(100_000_000L);
                        Files.write(target, new byte[10]);
                    });
                }));
            }
            start.countDown();
            for (Future<Path> result : results) {
                assertThat(result.get()).isEqualTo(dir.resolve("a.png"));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(loads).hasValue(1);
    }

    @Test
    void aFailedLoadLeavesNoFile() throws IOException {
        EncryptedBlobCache cache = new EncryptedBlobCache(dir, 100);

        try {
            cache.getOrLoad("a.png", target -> {
                Files.write(target, new byte[3]);
                throw new IOException("boom");
            });
        } catch (IOException expected) {
            // atteso
        }

        assertThat(cache.get("a.png")).isEmpty();
        assertThat(dir.toFile().list()).isEmpty();
    }
}
