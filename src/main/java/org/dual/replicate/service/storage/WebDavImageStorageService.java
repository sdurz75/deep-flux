package org.dual.replicate.service.storage;

import java.io.BufferedOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.OptionalLong;

import jakarta.annotation.PreDestroy;
import org.dual.replicate.domain.AppErrorSource;
import org.dual.replicate.i18n.Messages;
import org.dual.replicate.service.AppErrorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriUtils;

/**
 * Backend WebDAV ({@code storage.type=webdav}): i contenuti sono SEMPRE cifrati con una chiave simmetrica
 * ({@link ChunkedAesGcmCipher}, {@code storage.webdav.encryption-key}) prima di lasciare l'app, quindi il server
 * WebDAV non vede mai un'immagine leggibile. I nomi dei file restano in chiaro; perdere la chiave rende i binari
 * irrecuperabili. Una cache locale ({@link EncryptedBlobCache}) dei blob cifrati evita i round trip verso il server.
 *
 * <p>Solo {@code PUT}/{@code GET}/{@code HEAD}/{@code DELETE}/{@code MKCOL}/{@code MOVE} con il {@code RestClient} del
 * builder auto-configurato (timeout globali): nessuna libreria WebDAV.
 */
@Service
@ConditionalOnProperty(name = "storage.type", havingValue = "webdav")
public class WebDavImageStorageService extends AbstractImageStorageService {

    private static final Logger log = LoggerFactory.getLogger(WebDavImageStorageService.class);
    private static final HttpMethod MKCOL = HttpMethod.valueOf("MKCOL");
    private static final HttpMethod MOVE = HttpMethod.valueOf("MOVE");

    private final RestClient dav;
    private final String baseUrl;
    private final ChunkedAesGcmCipher cipher;
    private final EncryptedBlobCache cache;
    private final AppErrorService appErrors;
    private final Set<String> collectionsReady = ConcurrentHashMap.newKeySet();
    /** Un solo thread: il riscaldamento della cache dei video non deve saturare la banda verso WebDAV. */
    private final ExecutorService warmer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "webdav-cache-warmer");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<String> warming = ConcurrentHashMap.newKeySet();

    public WebDavImageStorageService(
            @Value("${storage.webdav.url:}") String url,
            @Value("${storage.webdav.username:}") String username,
            @Value("${storage.webdav.password:}") String password,
            @Value("${storage.webdav.encryption-key:}") String encryptionKey,
            @Value("${storage.webdav.cache.dir:./data/cache}") String cacheDir,
            @Value("${storage.webdav.cache.max-size:2GB}") DataSize cacheMaxSize,
            Messages messages, RestClient.Builder restClientBuilder, AppErrorService appErrors) throws IOException {
        super(messages, restClientBuilder);
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("storage.type=webdav richiede storage.webdav.url");
        }
        this.baseUrl = url.endsWith("/") ? url : url + "/";
        this.cipher = new ChunkedAesGcmCipher(ChunkedAesGcmCipher.keyFromBase64(encryptionKey));
        this.cache = new EncryptedBlobCache(Path.of(cacheDir), cacheMaxSize.toBytes());
        this.appErrors = appErrors;
        RestClient.Builder builder = restClientBuilder.clone();
        if (username != null && !username.isBlank()) {
            builder.defaultHeaders(headers -> headers.setBasicAuth(username, password == null ? "" : password));
        }
        this.dav = builder.build();
    }

    // --- IImageStorageService -------------------------------------------------------------------------------------

    @Override
    public OptionalLong size(String filename) {
        checkFilename(filename);
        try {
            return OptionalLong.of(cipher.plainSize(source(filename)));
        } catch (NoSuchFileException e) {
            return OptionalLong.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(messages.get("imagestorage.error.readImage", filename), e);
        }
    }

    @Override
    public InputStream openRange(String filename, long offset, long length) throws IOException {
        checkFilename(filename);
        return cipher.decryptRange(source(filename), offset, length);
    }

    @PreDestroy
    void shutdown() {
        warmer.shutdownNow();
    }

    // --- migrazione dal filesystem locale (vedi LocalToWebDavMigrator) ---------------------------------------------

    /** {@code true} se {@code filename} esiste SUL SERVER (interroga WebDAV, non la cache: serve all'idempotenza). */
    boolean existsRemotely(String filename) throws IOException {
        checkFilename(filename);
        try {
            new WebDavBlobSource(filename).length();
            return true;
        } catch (NoSuchFileException e) {
            return false;
        }
    }

    /** Dimensione in chiaro di {@code filename} come risulta dal SERVER (mai dalla cache): verifica di un upload. */
    long remotePlainSize(String filename) throws IOException {
        checkFilename(filename);
        return cipher.plainSize(new WebDavBlobSource(filename));
    }

    /** Cifra e carica {@code file} come {@code filename} (come una scrittura normale, cache write-through inclusa). */
    void importFile(String filename, Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            write(filename, in);
        }
    }

    // --- primitive di persistenza ---------------------------------------------------------------------------------

    /**
     * Cifra su un temporaneo locale (cosi' la lunghezza e' nota per il {@code PUT}), carica su {@code <nome>.part} e
     * sposta sul nome definitivo: sul server non resta mai un blob parziale col nome buono. A successo il temporaneo
     * diventa la voce di cache (write-through).
     */
    @Override
    protected void write(String filename, InputStream in) throws IOException {
        checkFilename(filename);
        Path temp = cache.newTempFile();
        try {
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp))) {
                cipher.encrypt(in, out);
            }
            ensureCollections(filename);
            String partName = filename + ".part";
            try {
                remote(() -> dav.put().uri(uri(partName)).body(new FileSystemResource(temp)).retrieve().toBodilessEntity());
                remote(() -> dav.method(MOVE).uri(uri(partName)).header("Destination", uri(filename).toString())
                        .header("Overwrite", "T").retrieve().toBodilessEntity());
            } catch (IOException | RuntimeException e) {
                deleteRemoteQuietly(partName);
                throw e;
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        try {
            cache.adopt(filename, temp);
        } catch (IOException | RuntimeException e) {
            // Il file e' su WebDAV: un problema di cache non deve far fallire il salvataggio.
            appErrors.record(AppErrorSource.STORAGE, "cacheBlob", e);
            Files.deleteIfExists(temp);
        }
    }

    @Override
    protected void remove(String filename) throws IOException {
        cache.remove(filename);
        remote(() -> dav.delete().uri(uri(filename)).exchange((request, response) -> {
            if (response.getStatusCode().isError() && response.getStatusCode().value() != 404) {
                throw new IOException("HTTP " + response.getStatusCode().value() + " su DELETE " + filename);
            }
            return null;
        }));
    }

    // --- sorgente: cache o server ---------------------------------------------------------------------------------

    /**
     * Cache hit: il file locale. Miss: si scarica l'intero blob in cache (poi si serve da li', Range compresi); un blob
     * oltre il tetto, o un problema di cache, ripiega sulla lettura diretta dei range da WebDAV. Un VIDEO in miss non
     * blocca la richiesta sul download intero (il browser chiede solo i primi byte per il primo frame): si servono i
     * range da WebDAV e la cache si riscalda in background ({@link #warmInBackground}).
     */
    private EncryptedBlobSource source(String filename) throws IOException {
        Optional<Path> hit = cache.get(filename);
        if (hit.isPresent()) {
            return new FileBlobSource(hit.get());
        }
        WebDavBlobSource remote = new WebDavBlobSource(filename);
        if (!cache.enabled() || remote.length() > cache.maxBytes()) {
            return remote;
        }
        if (isVideo(filename)) {
            warmInBackground(filename);
            return remote;
        }
        try {
            return new FileBlobSource(cache.getOrLoad(filename, remote::downloadTo));
        } catch (NoSuchFileException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            appErrors.record(AppErrorSource.STORAGE, "cacheBlob", e);
            return remote;
        }
    }

    private static boolean isVideo(String filename) {
        return filename.toLowerCase(java.util.Locale.ROOT).endsWith(".mp4");
    }

    /** Scarica il blob in cache senza bloccare chi legge; una sola volta per file alla volta. */
    private void warmInBackground(String filename) {
        if (!warming.add(filename)) {
            return;
        }
        try {
            warmer.execute(() -> {
                try {
                    cache.getOrLoad(filename, new WebDavBlobSource(filename)::downloadTo);
                } catch (NoSuchFileException gone) {
                    // cancellato nel frattempo: niente da riscaldare
                } catch (IOException | RuntimeException e) {
                    appErrors.record(AppErrorSource.STORAGE, "warmCache", e);
                } finally {
                    warming.remove(filename);
                }
            });
        } catch (RejectedExecutionException shuttingDown) {
            warming.remove(filename);
        }
    }

    private final class WebDavBlobSource implements EncryptedBlobSource {

        private final String filename;
        /** Immutabile per un dato nome: una HEAD sola per istanza (size + range nella stessa richiesta). */
        private long length = -1;

        WebDavBlobSource(String filename) {
            this.filename = filename;
        }

        @Override
        public long length() throws IOException {
            if (length < 0) {
                length = fetchLength();
            }
            return length;
        }

        private long fetchLength() throws IOException {
            return remote(() -> dav.head().uri(uri(filename)).exchange((request, response) -> {
                if (response.getStatusCode().value() == 404) {
                    throw new NoSuchFileException(filename);
                }
                if (response.getStatusCode().isError()) {
                    throw new IOException("HTTP " + response.getStatusCode().value() + " su HEAD " + filename);
                }
                long length = response.getHeaders().getContentLength();
                if (length < 0) {
                    throw new IOException("WebDAV non ha indicato la dimensione di " + filename);
                }
                return length;
            }));
        }

        @Override
        public InputStream read(long offset, long len) throws IOException {
            return remote(() -> dav.get().uri(uri(filename))
                    .header(HttpHeaders.RANGE, "bytes=" + offset + "-" + (offset + len - 1))
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 404 || response.getStatusCode().isError()) {
                            response.close();
                            throw status == 404 ? new NoSuchFileException(filename)
                                    : new IOException("HTTP " + status + " su GET " + filename);
                        }
                        InputStream body = response.getBody();
                        if (status == 200) {
                            // Il server ha ignorato il Range: si scarta fino a offset.
                            try {
                                body.skipNBytes(offset);
                            } catch (IOException e) {
                                response.close();
                                throw e;
                            }
                            body = Streams.limit(body, len);
                        }
                        return new FilterInputStream(body) {
                            @Override
                            public void close() throws IOException {
                                try {
                                    super.close();
                                } finally {
                                    response.close();
                                }
                            }
                        };
                        // close=false: la risposta resta aperta finche' chi legge non chiude lo stream.
                    }, false));
        }

        void downloadTo(Path target) throws IOException {
            remote(() -> dav.get().uri(uri(filename)).exchange((request, response) -> {
                if (response.getStatusCode().value() == 404) {
                    throw new NoSuchFileException(filename);
                }
                if (response.getStatusCode().isError()) {
                    throw new IOException("HTTP " + response.getStatusCode().value() + " su GET " + filename);
                }
                Files.copy(response.getBody(), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return null;
            }));
        }
    }

    // --- HTTP -----------------------------------------------------------------------------------------------------

    /**
     * MKCOL della collezione base e delle due sotto-collezioni dello shard ({@code ab}, {@code ab/cd}), una volta per
     * esecuzione ciascuna, come TENTATIVO: qualunque risposta HTTP e' tollerata (405 = esiste gia'; ma anche 409 sulla
     * radice di alcuni server, es. Yandex, o 301/403 su cartelle non creabili). Se la collezione manca davvero fallisce
     * il {@code PUT} successivo, con un errore piu' chiaro. Un errore di trasporto (server irraggiungibile) invece si
     * propaga.
     */
    private void ensureCollections(String filename) throws IOException {
        String[] parts = shardPath(filename).split("/");
        mkcolOnce("");
        mkcolOnce(parts[0]);
        mkcolOnce(parts[0] + "/" + parts[1]);
    }

    private void mkcolOnce(String relative) throws IOException {
        if (collectionsReady.contains(relative)) {
            return;
        }
        URI target = URI.create(relative.isEmpty() ? baseUrl : baseUrl + relative + "/");
        int status = remote(() -> dav.method(MKCOL).uri(target)
                .exchange((request, response) -> response.getStatusCode().value()));
        if (status >= 400 && status != 405) {
            log.debug("MKCOL {} -> HTTP {}: ignorato, la collezione si presume esistente", target, status);
        }
        collectionsReady.add(relative);
    }

    private void deleteRemoteQuietly(String name) {
        try {
            remote(() -> dav.delete().uri(uri(name)).retrieve().toBodilessEntity());
        } catch (IOException | RuntimeException ignored) {
            // best-effort: e' gia' un percorso di errore
        }
    }

    private URI uri(String name) {
        StringBuilder path = new StringBuilder(baseUrl);
        String[] segments = shardPath(name.endsWith(".part") ? name.substring(0, name.length() - 5) : name).split("/");
        for (int i = 0; i < segments.length; i++) {
            String segment = i == segments.length - 1 ? name : segments[i];
            path.append(i == 0 ? "" : "/").append(UriUtils.encodePathSegment(segment, StandardCharsets.UTF_8));
        }
        return URI.create(path.toString());
    }

    @FunctionalInterface
    private interface RemoteCall<T> {
        T call() throws IOException;
    }

    /**
     * RestClient incapsula le IOException dell'exchange in ResourceAccessException e le risposte d'errore in
     * RestClientResponseException: qui tornano IOException (con la NoSuchFileException originale, per il 404).
     */
    private static <T> T remote(RemoteCall<T> call) throws IOException {
        try {
            return call.call();
        } catch (ResourceAccessException e) {
            throw e.getCause() instanceof IOException io ? io : new IOException(e);
        } catch (RestClientException e) {
            throw new IOException(e.getMessage(), e);
        }
    }
}
