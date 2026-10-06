package org.dual.replicate.app.training.adapter.out.huggingface;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.dual.replicate.app.training.domain.HfAccount;
import org.dual.replicate.app.training.domain.HuggingFaceException;
import org.dual.replicate.app.training.domain.WeightsFile;
import org.dual.replicate.app.training.port.out.IHuggingFaceRepos;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.dual.replicate.core.kernel.remote.RemoteServiceException.Kind;
import org.dual.replicate.core.kernel.remote.RestRemoteClient;
import org.dual.replicate.core.kernel.remote.RetryPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.AbstractResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * {@link IHuggingFaceRepos} su HuggingFace (REST, {@code RestClient}). Il token non e' una configurazione dell'app: arriva a ogni chiamata, scelto dall'utente fra
 * quelli salvati in /tokens, e non viene mai messo in un messaggio d'errore ne' in un log.
 */
@Component
class HuggingFaceClient extends RestRemoteClient implements IHuggingFaceRepos {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT = new ParameterizedTypeReference<>() {
    };

    private static final MediaType LFS_JSON = MediaType.parseMediaType("application/vnd.git-lfs+json");
    private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");
    private static final String BRANCH = "main";
    /** HuggingFace decide da un campione dei primi byte se un file va in LFS; e' il campione che manda anche il suo client ufficiale. */
    private static final int SAMPLE_BYTES = 512;

    private final RestClient restClient;
    private final Messages messages;
    /** L'indirizzo del sito (senza {@code /api}): l'endpoint LFS sta li', non sotto l'API. */
    private final String hubBaseUrl;

    HuggingFaceClient(RestClient.Builder restClientBuilder, @Value("${huggingface.api-base-url:https://huggingface.co/api}") String apiBaseUrl,
                      Messages messages) {
        super("huggingface", messages, HuggingFaceException::new, RetryPolicy.DEFAULT);
        this.restClient = restClientBuilder.baseUrl(apiBaseUrl).build();
        this.messages = messages;
        this.hubBaseUrl = apiBaseUrl.replaceFirst("/api/?$", "");
    }

    @Override
    public HfAccount whoami(String token) {
        Map<String, Object> body = remote.call("whoami", () -> restClient.get().uri("/whoami-v2").headers(h -> h.setBearerAuth(token)).retrieve()
                .body(JSON_OBJECT));
        Object name = body == null ? null : body.get("name");
        if (!(name instanceof String username) || username.isBlank()) {
            throw new HuggingFaceException(messages.get("huggingface.error.noUsername"), null, Kind.PERMANENT);
        }
        return new HfAccount(username, roleOf(body));
    }

    /** {@code auth.accessToken.role} ({@code read}, {@code write}, {@code fineGrained}...); {@code null} se la forma e' inattesa. */
    private static String roleOf(Map<String, Object> body) {
        return body.get("auth") instanceof Map<?, ?> auth && auth.get("accessToken") instanceof Map<?, ?> accessToken
                && accessToken.get("role") instanceof String role ? role : null;
    }

    @Override
    public void createModelRepo(String token, String repoName, boolean isPrivate) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "model");
        body.put("name", repoName);
        body.put("private", isPrivate);
        remote.call("createModelRepo", () -> {
            try {
                restClient.post().uri("/repos/create").headers(h -> h.setBearerAuth(token)).contentType(MediaType.APPLICATION_JSON).body(body)
                        .retrieve().toBodilessEntity();
            } catch (HttpClientErrorException.Conflict e) {
                // Esiste gia': e' quello che si voleva (e un ritentativo dopo un timeout lo trova creato dal tentativo precedente).
                return false;
            }
            return true;
        });
    }

    @Override
    public Optional<List<String>> repoFiles(String token, String repoId) {
        String[] userAndName = repoId.split("/", 2);
        if (userAndName.length != 2) {
            return Optional.empty();
        }
        return remote.call("repoFiles", () -> {
            try {
                Map<String, Object> repo = restClient.get().uri("/models/{user}/{name}", userAndName[0], userAndName[1]).headers(h -> h.setBearerAuth(token))
                        .retrieve().body(JSON_OBJECT);
                return Optional.of(filesOf(repo));
            } catch (HttpClientErrorException.NotFound e) {
                return Optional.<List<String>>empty();
            }
        });
    }

    /** {@code siblings[].rfilename}: l'elenco dei file che HuggingFace riporta nella scheda del repo; vuoto se la forma e' inattesa. */
    private static List<String> filesOf(Map<String, Object> repo) {
        if (repo == null || !(repo.get("siblings") instanceof List<?> siblings)) {
            return List.of();
        }
        List<String> files = new ArrayList<>();
        for (Object sibling : siblings) {
            if (sibling instanceof Map<?, ?> entry && entry.get("rfilename") instanceof String name) {
                files.add(name);
            }
        }
        return files;
    }

    // --- caricamento a mano dei pesi (protocollo del client ufficiale: preupload, LFS batch, PUT, verify, commit) ---------------------------------------

    @Override
    public void uploadWeights(String token, String repoId, WeightsFile weights, String commitMessage) {
        String[] userAndName = repoId.split("/", 2);
        if (userAndName.length != 2) {
            throw new HuggingFaceException(messages.get("huggingface.error.repoId", repoId));
        }
        String user = userAndName[0];
        String repo = userAndName[1];
        String path = weights.name();
        if (!path.matches("[A-Za-z0-9._-]{1,200}")) {
            throw new HuggingFaceException(messages.get("huggingface.error.fileName", path));
        }

        // 1. L'impronta (HuggingFace la vuole PRIMA di ricevere il file) e il campione dei primi byte: una lettura del file.
        Fingerprint fingerprint = weights.read(in -> fingerprintOf(in, weights.size()));

        // 2. Come va caricato: un file di pesi e' sempre LFS (sopra la soglia e per estensione).
        Map<String, Object> preupload = remote.call("preuploadWeights", () -> restClient.post().uri("/models/{user}/{repo}/preupload/{revision}", user, repo, BRANCH)
                .headers(h -> h.setBearerAuth(token)).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("files", List.of(Map.of("path", path, "sample", Base64.getEncoder().encodeToString(fingerprint.sample()), "size", fingerprint.size()))))
                .retrieve().body(JSON_OBJECT));
        if (!"lfs".equals(uploadModeOf(preupload, path)) || shouldIgnore(preupload)) {
            throw new HuggingFaceException(messages.get("huggingface.error.notLfs", path), null, Kind.PERMANENT);
        }

        // 3. Le istruzioni LFS. Senza "actions" HuggingFace ha gia' questo contenuto: si salta il trasferimento e resta il commit.
        Map<String, Object> batch = remote.call("lfsBatch", () -> restClient.post().uri(hubBaseUrl + "/{user}/{repo}.git/info/lfs/objects/batch", user, repo)
                .headers(h -> h.setBearerAuth(token)).accept(LFS_JSON).contentType(LFS_JSON)
                .body(Map.of("operation", "upload", "transfers", List.of("basic", "multipart"), "hash_algo", "sha256",
                        "ref", Map.of("name", BRANCH), "objects", List.of(Map.of("oid", fingerprint.oid(), "size", fingerprint.size()))))
                .retrieve().body(JSON_OBJECT));
        Map<String, Object> object = firstObjectOf(batch);
        if (object.get("error") instanceof Map<?, ?> error) {
            throw new HuggingFaceException(messages.get("huggingface.error.lfsRefused", error.get("message")), null, Kind.PERMANENT);
        }
        if (object.get("actions") instanceof Map<?, ?> actions && actions.get("upload") instanceof Map<?, ?>) {
            Map<String, Object> upload = asMap(actions.get("upload"));
            // 4. Il trasferimento: un solo PUT o, se il server lo chiede (chunk_size), un PUT per parte e la chiusura. Il file si riscarica: e' la seconda lettura.
            remote.call("uploadWeights", RetryPolicy.NONE, () -> weights.read(in -> {
                try {
                    transfer(in, upload, fingerprint);
                } catch (RestClientResponseException e) {
                    // Questo codice gira DENTRO la chiamata remota che scarica i pesi (Replicate): una risposta HTTP d'errore viene da HuggingFace o dal suo storage firmato,
                    // e senza classificarla qui il traduttore di Replicate la scambierebbe per un guasto suo (evento, toast e sorgente sbagliati).
                    throw errors.apply(e);
                }
                return null;
            }));
            // 5. La verifica, se il server la vuole.
            if (actions.get("verify") instanceof Map<?, ?>) {
                String verifyUrl = hubUrlOf(asMap(actions.get("verify")));
                remote.call("verifyWeights", () -> restClient.post().uri(URI.create(verifyUrl)).headers(h -> h.setBearerAuth(token)).accept(LFS_JSON)
                        .contentType(LFS_JSON).body(Map.of("oid", fingerprint.oid(), "size", fingerprint.size())).retrieve().toBodilessEntity());
            }
        }

        // 6. Il commit che mette il file nel repo. Non si ritenta: un ritentativo dopo una risposta persa farebbe un secondo commit.
        String ndjson = "{\"key\":\"header\",\"value\":{\"summary\":" + quote(commitMessage) + ",\"description\":\"\"}}\n"
                + "{\"key\":\"lfsFile\",\"value\":{\"path\":" + quote(path) + ",\"algo\":\"sha256\",\"oid\":\"" + fingerprint.oid() + "\",\"size\":"
                + fingerprint.size() + "}}\n";
        remote.call("commitWeights", RetryPolicy.NONE, () -> restClient.post().uri("/models/{user}/{repo}/commit/{revision}", user, repo, BRANCH)
                .headers(h -> h.setBearerAuth(token)).contentType(NDJSON).body(ndjson).retrieve().toBodilessEntity());
    }

    private void transfer(InputStream in, Map<String, Object> upload, Fingerprint fingerprint) {
        String href = hrefOf(upload);
        Map<String, Object> header = upload.get("header") instanceof Map<?, ?> ? asMap(upload.get("header")) : Map.of();
        if (header.get("chunk_size") == null) {
            put(URI.create(href), in, fingerprint.size());
            return;
        }
        long chunkSize = Long.parseLong(String.valueOf(header.get("chunk_size")));
        List<String> partUrls = header.entrySet().stream().filter(e -> e.getKey().matches("\\d+")).sorted((a, b) -> Long.compare(Long.parseLong(a.getKey()),
                Long.parseLong(b.getKey()))).map(e -> String.valueOf(e.getValue())).toList();
        if (chunkSize <= 0 || partUrls.size() != (fingerprint.size() + chunkSize - 1) / chunkSize) {
            throw new HuggingFaceException(messages.get("huggingface.error.lfsMalformed"), null, Kind.PERMANENT);
        }
        List<Map<String, Object>> parts = new ArrayList<>();
        long remaining = fingerprint.size();
        for (int i = 0; i < partUrls.size(); i++) {
            long length = Math.min(chunkSize, remaining);
            String etag = put(URI.create(partUrls.get(i)), new Slice(in, length), length);
            if (etag == null || etag.isBlank()) {
                throw new HuggingFaceException(messages.get("huggingface.error.lfsMalformed"), null, Kind.PERMANENT);
            }
            parts.add(Map.of("partNumber", i + 1, "etag", etag));
            remaining -= length;
        }
        restClient.post().uri(URI.create(href)).accept(LFS_JSON).contentType(LFS_JSON).body(Map.of("oid", fingerprint.oid(), "parts", parts))
                .retrieve().toBodilessEntity();
    }

    /** PUT in streaming di {@code length} byte (lunghezza nota: senza, il converter leggerebbe tutto lo stream solo per misurarlo); ritorna l'ETag della risposta. */
    private String put(URI uri, InputStream body, long length) {
        return restClient.put().uri(uri).contentType(MediaType.APPLICATION_OCTET_STREAM).body(new StreamResource(body, length)).retrieve().toBodilessEntity()
                .getHeaders().getFirst(HttpHeaders.ETAG);
    }

    /** SHA-256 e primi byte del file, leggendolo una volta; un numero di byte diverso dalla dimensione dichiarata e' un download troncato. */
    private Fingerprint fingerprintOf(InputStream in, long expectedSize) throws IOException {
        MessageDigest digest = sha256();
        byte[] sample = new byte[SAMPLE_BYTES];
        int sampled = 0;
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) > 0) {
            digest.update(buffer, 0, read);
            if (sampled < SAMPLE_BYTES) {
                int take = Math.min(read, SAMPLE_BYTES - sampled);
                System.arraycopy(buffer, 0, sample, sampled, take);
                sampled += take;
            }
            total += read;
        }
        if (total != expectedSize) {
            throw new HuggingFaceException(messages.get("huggingface.error.sizeMismatch", total, expectedSize), null, Kind.TRANSIENT);
        }
        return new Fingerprint(HexFormat.of().formatHex(digest.digest()), total, Arrays.copyOf(sample, sampled));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 c'e' in ogni JDK
        }
    }

    private static String uploadModeOf(Map<String, Object> preupload, String path) {
        if (preupload != null && preupload.get("files") instanceof List<?> files) {
            for (Object file : files) {
                if (file instanceof Map<?, ?> entry && path.equals(entry.get("path")) && entry.get("uploadMode") instanceof String mode) {
                    return mode;
                }
            }
        }
        return null;
    }

    private static boolean shouldIgnore(Map<String, Object> preupload) {
        return preupload.get("files") instanceof List<?> files && files.get(0) instanceof Map<?, ?> entry && Boolean.TRUE.equals(entry.get("shouldIgnore"));
    }

    private Map<String, Object> firstObjectOf(Map<String, Object> batch) {
        if (batch != null && batch.get("objects") instanceof List<?> objects && !objects.isEmpty() && objects.get(0) instanceof Map<?, ?>) {
            return asMap(objects.get(0));
        }
        throw new HuggingFaceException(messages.get("huggingface.error.lfsMalformed"), null, Kind.PERMANENT);
    }

    /**
     * L'indirizzo di un'azione che richiede il token (la verifica): deve stare sullo stesso host dell'hub, altrimenti una risposta anomala (o riscritta da un proxy)
     * manderebbe altrove il token di scrittura. Gli URL dei PUT non lo richiedono: sono firmati e NON portano il token.
     */
    private String hubUrlOf(Map<String, Object> action) {
        String href = hrefOf(action);
        URI hub = URI.create(hubBaseUrl);
        URI target = URI.create(href);
        boolean sameOrigin = hub.getScheme() != null && hub.getScheme().equalsIgnoreCase(target.getScheme())
                && hub.getHost() != null && hub.getHost().equalsIgnoreCase(target.getHost()) && hub.getPort() == target.getPort();
        if (!sameOrigin) {
            throw new HuggingFaceException(messages.get("huggingface.error.lfsMalformed"), null, Kind.PERMANENT);
        }
        return href;
    }

    private String hrefOf(Map<String, Object> action) {
        if (action.get("href") instanceof String href && !href.isBlank()) {
            return href;
        }
        throw new HuggingFaceException(messages.get("huggingface.error.lfsMalformed"), null, Kind.PERMANENT);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    /** Una stringa JSON tra virgolette, con gli escape minimi richiesti dal formato. */
    private static String quote(String text) {
        StringBuilder quoted = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> quoted.append(c < 0x20 ? "\\u%04x".formatted((int) c) : String.valueOf(c));
            }
        }
        return quoted.append('"').toString();
    }

    /** Impronta del file: SHA-256 in esadecimale (l'"oid" di LFS), dimensione e primi byte. */
    private record Fingerprint(String oid, long size, byte[] sample) {
    }

    /** Un tratto di {@code length} byte di uno stream piu' grande: non lo chiude (le parti successive leggono dallo stesso). */
    private static final class Slice extends FilterInputStream {

        private long remaining;

        Slice(InputStream in, long length) {
            super(in);
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int b = super.read();
            if (b >= 0) {
                remaining--;
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int n = super.read(buffer, off, (int) Math.min(len, remaining));
            if (n > 0) {
                remaining -= n;
            }
            return n;
        }

        @Override
        public void close() {
            // lo stream sotto e' di chi ha creato la fetta
        }
    }

    /** Lo stream come corpo di una richiesta, con la lunghezza nota (senza, {@code AbstractResource} lo leggerebbe tutto per contarlo). */
    private static final class StreamResource extends AbstractResource {

        private final InputStream in;
        private final long length;

        StreamResource(InputStream in, long length) {
            this.in = in;
            this.length = length;
        }

        @Override
        public long contentLength() {
            return length;
        }

        @Override
        public boolean exists() {
            return true;
        }

        @Override
        public String getDescription() {
            return "pesi del LoRA";
        }

        @Override
        public InputStream getInputStream() {
            return in;
        }
    }
}
