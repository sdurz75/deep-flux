package org.dual.hexa.core.storage.adapter.out.webdav;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.dual.hexa.core.kernel.crypto.EncryptedBlobSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cache su disco dei blob CIFRATI di WebDAV, con tetto di dimensione ed eliminazione LRU: evita il round trip verso
 * il server a ogni richiesta di un'immagine gia' vista. Contiene lo stesso formato cifrato di WebDAV (mai chiaro): il
 * cifrario decifra da cache o da server allo stesso modo ({@link org.dual.hexa.core.kernel.crypto.EncryptedBlobSource}).
 *
 * <p>I nomi dei file sono immutabili e unici, quindi non serve invalidazione se non su {@link #remove}. L'indice LRU
 * e' in memoria e all'avvio si ricostruisce scandendo la cartella per data di modifica (aggiornata a ogni accesso).
 * Un file in lettura durante l'eliminazione resta leggibile (unlink su file aperto). {@code maxBytes == 0} la disattiva
 * (la cartella serve comunque per i temporanei di scrittura).
 */
final class EncryptedBlobCache {

    private static final Logger log = LoggerFactory.getLogger(EncryptedBlobCache.class);
    private static final String TEMP_SUFFIX = ".part";

    @FunctionalInterface
    interface Loader {
        void load(Path target) throws IOException;
    }

    private final Path dir;
    private final long maxBytes;
    /** Ordine di accesso: il primo e' il meno usato di recente. */
    private final LinkedHashMap<String, Long> index = new LinkedHashMap<>(16, 0.75f, true);
    private long totalBytes;
    private final ConcurrentHashMap<String, Object> loading = new ConcurrentHashMap<>();

    EncryptedBlobCache(Path dir, long maxBytes) throws IOException {
        this.dir = dir;
        this.maxBytes = Math.max(0, maxBytes);
        Files.createDirectories(dir);
        rebuildIndex();
    }

    boolean enabled() {
        return maxBytes > 0;
    }

    long maxBytes() {
        return maxBytes;
    }

    synchronized long totalBytes() {
        return totalBytes;
    }

    /** Il blob in cache, se c'e' (lo segna come usato di recente). */
    synchronized Optional<Path> get(String name) {
        if (index.get(name) == null) {
            return Optional.empty();
        }
        Path file = dir.resolve(name);
        if (!Files.isRegularFile(file)) {
            totalBytes -= index.remove(name);
            return Optional.empty();
        }
        try {
            Files.setLastModifiedTime(file, FileTime.from(Instant.now()));
        } catch (IOException ignored) {
            // best-effort: serve solo a far sopravvivere l'ordine LRU a un riavvio
        }
        return Optional.of(file);
    }

    /** File temporaneo nella cartella della cache (stesso filesystem: {@link #adopt} lo sposta in modo atomico). */
    Path newTempFile() throws IOException {
        return Files.createTempFile(dir, "blob-", TEMP_SUFFIX);
    }

    /**
     * Prende in carico {@code temp} (blob cifrato completo) come {@code name}. Se la cache e' disattivata o il blob
     * supera il tetto, il temporaneo viene semplicemente eliminato.
     */
    void adopt(String name, Path temp) throws IOException {
        long size = Files.size(temp);
        if (!enabled() || size > maxBytes) {
            Files.deleteIfExists(temp);
            return;
        }
        Files.move(temp, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        synchronized (this) {
            Long previous = index.put(name, size);
            totalBytes += size - (previous == null ? 0 : previous);
            evict(name);
        }
    }

    /**
     * Il blob in cache o, se manca, lo scarica con {@code loader}. Richieste concorrenti per lo stesso nome si
     * coalizzano: una sola esegue il download, le altre trovano il risultato.
     */
    Path getOrLoad(String name, Loader loader) throws IOException {
        Optional<Path> hit = get(name);
        if (hit.isPresent()) {
            return hit.get();
        }
        Object lock = loading.computeIfAbsent(name, k -> new Object());
        synchronized (lock) {
            try {
                hit = get(name);
                if (hit.isPresent()) {
                    return hit.get();
                }
                Path temp = newTempFile();
                try {
                    loader.load(temp);
                } catch (IOException | RuntimeException e) {
                    Files.deleteIfExists(temp);
                    throw e;
                }
                adopt(name, temp);
                return get(name).orElseThrow(() -> new IOException("Blob non in cache: " + name));
            } finally {
                loading.remove(name, lock);
            }
        }
    }

    synchronized void remove(String name) throws IOException {
        Long size = index.remove(name);
        if (size != null) {
            totalBytes -= size;
        }
        Files.deleteIfExists(dir.resolve(name));
    }

    /** Elimina i meno usati di recente finche' il totale rientra nel tetto (mai {@code keep}, appena inserito). */
    private void evict(String keep) {
        Iterator<Map.Entry<String, Long>> it = index.entrySet().iterator();
        while (totalBytes > maxBytes && it.hasNext()) {
            Map.Entry<String, Long> oldest = it.next();
            if (oldest.getKey().equals(keep)) {
                continue;
            }
            it.remove();
            totalBytes -= oldest.getValue();
            try {
                Files.deleteIfExists(dir.resolve(oldest.getKey()));
            } catch (IOException e) {
                log.warn("Cache blob: impossibile eliminare {}", oldest.getKey(), e);
            }
        }
    }

    private void rebuildIndex() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(Files::isRegularFile).forEach(file -> {
                if (file.getFileName().toString().endsWith(TEMP_SUFFIX)) {
                    try {
                        Files.deleteIfExists(file); // temporaneo orfano di un'esecuzione interrotta
                    } catch (IOException e) {
                        log.warn("Cache blob: impossibile eliminare il temporaneo {}", file, e);
                    }
                }
            });
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(EncryptedBlobCache::lastModified))
                    .forEach(file -> {
                        try {
                            long size = Files.size(file);
                            index.put(file.getFileName().toString(), size);
                            totalBytes += size;
                        } catch (IOException e) {
                            log.warn("Cache blob: file illeggibile {}", file, e);
                        }
                    });
        }
        synchronized (this) {
            evict(null);
        }
    }

    private static FileTime lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file);
        } catch (IOException e) {
            return FileTime.fromMillis(0);
        }
    }
}
