package org.hexa.core.storage.adapter.out.webdav;

import java.io.BufferedOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import org.hexa.core.events.domain.CoreEventSource;
import org.hexa.core.kernel.crypto.ChunkedAesGcmCipher;
import org.hexa.core.kernel.crypto.EncryptedBlobSource;
import org.hexa.core.storage.adapter.out.local.FileBlobSource;
import org.hexa.core.storage.adapter.out.local.Streams;
import org.hexa.core.storage.domain.StorageException;
import org.hexa.core.storage.domain.StorageNames;
import org.hexa.core.storage.port.out.IBlobBackend;
import org.hexa.core.storage.port.out.IBlobImportTarget;
import org.hexa.core.kernel.i18n.Messages;
import org.hexa.core.kernel.remote.RemoteCaller;
import org.hexa.core.kernel.remote.RemoteServiceException.Kind;
import org.hexa.core.kernel.remote.RestClientTranslator;
import org.hexa.core.kernel.remote.RetryPolicy;
import org.hexa.core.events.port.in.ISystemEvents;
import org.hexa.core.storage.adapter.out.local.FileBlobSource;
import org.hexa.core.storage.adapter.out.local.Streams;
import org.hexa.core.storage.domain.StorageException;
import org.hexa.core.storage.domain.StorageNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;
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
@Component
@ConditionalOnProperty(name = "storage.type", havingValue = "webdav")
public class WebDavBlobBackend implements IBlobBackend, IBlobImportTarget {

    private static final Logger log = LoggerFactory.getLogger(WebDavBlobBackend.class);
    private static final HttpMethod MKCOL = HttpMethod.valueOf("MKCOL");
    private static final HttpMethod MOVE = HttpMethod.valueOf("MOVE");

    private final Messages messages;
    private final RestClient dav;
    private final String baseUrl;
    private final ChunkedAesGcmCipher cipher;
    private final EncryptedBlobCache cache;
    private final ISystemEvents systemEvents;
    private final RestClientTranslator errors;
    private final RemoteCaller remote;
    private final Set<String> collectionsReady = ConcurrentHashMap.newKeySet();
    /** Un solo thread: il riscaldamento della cache dei video non deve saturare la banda verso WebDAV. */
    private final ExecutorService warmer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "webdav-cache-warmer");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<String> warming = ConcurrentHashMap.newKeySet();

    public WebDavBlobBackend(
            @Value("${storage.webdav.url:}") String url,
            @Value("${storage.webdav.username:}") String username,
            @Value("${storage.webdav.password:}") String password,
            @Value("${storage.webdav.encryption-key:}") String encryptionKey,
            @Value("${storage.webdav.cache.dir:./data/cache}") String cacheDir,
            @Value("${storage.webdav.cache.max-size:2GB}") DataSize cacheMaxSize,
            Messages messages, RestClient.Builder restClientBuilder, ISystemEvents systemEvents) throws IOException {
        this.messages = messages;
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("storage.type=webdav richiede storage.webdav.url");
        }
        this.baseUrl = url.endsWith("/") ? url : url + "/";
        this.cipher = new ChunkedAesGcmCipher(ChunkedAesGcmCipher.keyFromBase64(encryptionKey));
        this.cache = new EncryptedBlobCache(Path.of(cacheDir), cacheMaxSize.toBytes());
        this.systemEvents = systemEvents;
        // Classificazione e retry condivisi con Replicate/SearXNG (vedi remote/). Le letture per /images/** non ritentano
        // (RetryPolicy.NONE di default): il browser riprova da se' e un retry allungherebbe la richiesta; le scritture,
        // le cancellazioni e gli HEAD della migrazione passano da retrying(). NoSuchFileException = "non esiste", non un errore.
        this.errors = new RestClientTranslator("webdav", messages, StorageException::new);
        this.remote = RemoteCaller.builder(errors).retry(RetryPolicy.NONE).passThrough(NoSuchFileException.class).build();
        RestClient.Builder builder = restClientBuilder.clone();
        if (username != null && !username.isBlank()) {
            builder.defaultHeaders(headers -> headers.setBasicAuth(username, password == null ? "" : password));
        }
        this.dav = builder.build();
    }

    // --- IBlobBackend ------------------------------------------------------------------------------------------

    @Override
    public OptionalLong size(String filename) {
        StorageNames.checkFilename(filename);
        try {
            return OptionalLong.of(cipher.plainSize(source(filename)));
        } catch (NoSuchFileException e) {
            return OptionalLong.empty();
        } catch (IOException e) {
            throw new StorageException(messages.get("imagestorage.error.readImage", filename), e, Kind.PERMANENT);
        }
    }

    @Override
    public InputStream openRange(String filename, long offset, long length) throws IOException {
        StorageNames.checkFilename(filename);
        return cipher.decryptRange(source(filename), offset, length);
    }

    @PreDestroy
    void shutdown() {
        warmer.shutdownNow();
    }

    // --- migrazione dal filesystem locale (vedi LocalToWebDavMigrator) ---------------------------------------------

    /** {@code true} se {@code filename} esiste SUL SERVER (interroga WebDAV, non la cache: serve all'idempotenza). */
    @Override
    public boolean existsRemotely(String filename) throws IOException {
        StorageNames.checkFilename(filename);
        try {
            retrying(() -> new WebDavBlobSource(filename).length());
            return true;
        } catch (NoSuchFileException e) {
            return false;
        }
    }

    /** Dimensione in chiaro di {@code filename} come risulta dal SERVER (mai dalla cache): verifica di un upload. */
    @Override
    public long remotePlainSize(String filename) throws IOException {
        StorageNames.checkFilename(filename);
        return retrying(() -> cipher.plainSize(new WebDavBlobSource(filename)));
    }

    /** Cifra e carica {@code file} come {@code filename} (come una scrittura normale, cache write-through inclusa). */
    @Override
    public void importFile(String filename, Path file) throws IOException {
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
    public void write(String filename, InputStream in) throws IOException {
        StorageNames.checkFilename(filename);
        Path temp = cache.newTempFile();
        try {
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp))) {
                cipher.encrypt(in, out);
            }
            ensureCollections(filename);
            String partName = filename + ".part";
            try {
                retrying(() -> dav.put().uri(uri(partName)).body(new FileSystemResource(temp)).retrieve().toBodilessEntity());
                retrying(() -> dav.method(MOVE).uri(uri(partName)).header("Destination", uri(filename).toString())
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
            systemEvents.record(CoreEventSource.STORAGE, "cacheBlob", e);
            Files.deleteIfExists(temp);
        }
    }

    @Override
    public void remove(String filename) throws IOException {
        cache.remove(filename);
        deleteRemote(filename);
    }

    /** DELETE con retry; un 404 (gia' assente) non e' un errore. */
    private void deleteRemote(String name) throws IOException {
        retrying(() -> dav.delete().uri(uri(name)).exchange((request, response) -> {
            int status = response.getStatusCode().value();
            if (response.getStatusCode().isError() && status != 404) {
                throw httpError(status, "DELETE " + name);
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
            systemEvents.record(CoreEventSource.STORAGE, "cacheBlob", e);
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
                    systemEvents.record(CoreEventSource.STORAGE, "warmCache", e);
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
                    throw httpError(response.getStatusCode().value(), "HEAD " + filename);
                }
                long length = response.getHeaders().getContentLength();
                if (length < 0) {
                    throw new StorageException(messages.get("imagestorage.error.webdavNoLength", filename), null, Kind.PERMANENT);
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
                            if (status == 404) {
                                throw new NoSuchFileException(filename);
                            }
                            throw httpError(status, "GET " + filename);
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
                    throw httpError(response.getStatusCode().value(), "GET " + filename);
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
        String[] parts = StorageNames.shardPath(filename).split("/");
        mkcolOnce("");
        mkcolOnce(parts[0]);
        mkcolOnce(parts[0] + "/" + parts[1]);
    }

    private void mkcolOnce(String relative) throws IOException {
        if (collectionsReady.contains(relative)) {
            return;
        }
        URI target = URI.create(relative.isEmpty() ? baseUrl : baseUrl + relative + "/");
        int status = retrying(() -> dav.method(MKCOL).uri(target)
                .exchange((request, response) -> response.getStatusCode().value()));
        if (status >= 400 && status != 405) {
            log.debug("MKCOL {} -> HTTP {}: ignorato, la collezione si presume esistente", target, status);
        }
        collectionsReady.add(relative);
    }

    /** Best-effort (e' gia' un percorso di errore), ma MAI muto: un {@code .part} orfano resta registrato. */
    private void deleteRemoteQuietly(String name) {
        try {
            deleteRemote(name);
        } catch (IOException | RuntimeException e) {
            systemEvents.record(CoreEventSource.STORAGE, "cleanupPart", e);
        }
    }

    private URI uri(String name) {
        StringBuilder path = new StringBuilder(baseUrl);
        String[] segments = StorageNames.shardPath(name.endsWith(".part") ? name.substring(0, name.length() - 5) : name).split("/");
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

    /** Chiamata remota senza retry; dichiara IOException perche' la {@code NoSuchFileException} del 404 esce cosi' com'e'. */
    private <T> T remote(RemoteCall<T> call) throws IOException {
        return remote.call("webdav", call::call);
    }

    /** Come {@link #remote}, ma ritenta gli errori transitori (idempotenti: PUT su .part, MOVE, DELETE, MKCOL, HEAD). */
    private <T> T retrying(RemoteCall<T> call) throws IOException {
        return remote.call("webdav", RetryPolicy.DEFAULT, call::call);
    }

    private StorageException httpError(int status, String what) {
        return (StorageException) errors.httpStatus(status, what);
    }
}
