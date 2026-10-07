package org.hexa.app.training.adapter.out.huggingface;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Random;

import org.hexa.app.training.domain.HuggingFaceException;
import org.hexa.app.training.domain.WeightsFile;
import org.hexa.core.kernel.i18n.Messages;
import org.hexa.core.kernel.remote.RemoteServiceException.Kind;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Il caricamento dei pesi (preupload, LFS batch, PUT singolo o per parti, verify, commit) contro un server finto: nessun repo vero, nessun byte spedito. Le forme
 * sono quelle del client ufficiale ({@code huggingface_hub}: {@code lfs.py}, {@code _commit_api.py}), da verificare alla prima esecuzione reale, con permesso:
 * i test provano che il codice le rispetta, non che HuggingFace le accetti.
 */
class HuggingFaceClientUploadTest {

    private static final String BASE = "http://hf.test/api";
    private static final String HUB = "http://hf.test";
    private static final String REPO = "sandro/lora";
    private static final String BATCH = HUB + "/sandro/lora.git/info/lfs/objects/batch";
    private static final String PREUPLOAD = BASE + "/models/sandro/lora/preupload/main";
    private static final String COMMIT = BASE + "/models/sandro/lora/commit/main";
    private static final MediaType LFS = MediaType.parseMediaType("application/vnd.git-lfs+json");

    private final Messages messages = mock(Messages.class);
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final HuggingFaceClient client;

    HuggingFaceClientUploadTest() {
        when(messages.get(anyString(), any(Object[].class))).thenAnswer(i -> i.getArgument(0));
        when(messages.get(anyString())).thenAnswer(i -> i.getArgument(0));
        client = new HuggingFaceClient(builder, BASE, messages);
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        new Random(42).nextBytes(bytes);
        return bytes;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static final String PREUPLOAD_LFS = "{\"files\":[{\"path\":\"flux-lora.safetensors\",\"uploadMode\":\"lfs\",\"shouldIgnore\":false}]}";

    // --- caso buono: un solo PUT ---------------------------------------------------------------------------------

    @Test
    void aSinglePutUploadFollowsTheOfficialClientsSequence() throws Exception {
        byte[] data = randomBytes(3000);
        String oid = sha256(data);
        FakeWeights weights = new FakeWeights(data, data.length);

        server.expect(ExpectedCount.once(), requestTo(PREUPLOAD)).andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer hf_tok"))
                .andExpect(jsonPath("$.files[0].path").value("flux-lora.safetensors"))
                .andExpect(jsonPath("$.files[0].size").value(3000))
                .andExpect(jsonPath("$.files[0].sample").value(Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(data, 512))))
                .andRespond(withSuccess(PREUPLOAD_LFS, MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(BATCH)).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer hf_tok"))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, LFS.toString())).andExpect(header(HttpHeaders.ACCEPT, LFS.toString()))
                .andExpect(jsonPath("$.operation").value("upload")).andExpect(jsonPath("$.hash_algo").value("sha256"))
                .andExpect(jsonPath("$.transfers[0]").value("basic")).andExpect(jsonPath("$.transfers[1]").value("multipart"))
                .andExpect(jsonPath("$.ref.name").value("main"))
                .andExpect(jsonPath("$.objects[0].oid").value(oid)).andExpect(jsonPath("$.objects[0].size").value(3000))
                .andRespond(withSuccess("{\"transfer\":\"basic\",\"objects\":[{\"oid\":\"" + oid + "\",\"size\":3000,\"actions\":{"
                        + "\"upload\":{\"href\":\"http://s3.test/put/abc\",\"header\":{}},\"verify\":{\"href\":\"" + HUB + "/verify\"}}}]}", LFS));
        // Il PUT va allo storage con i byte esatti e SENZA il token (e' un indirizzo firmato di un altro host).
        server.expect(ExpectedCount.once(), requestTo("http://s3.test/put/abc")).andExpect(method(HttpMethod.PUT)).andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andExpect(header(HttpHeaders.CONTENT_LENGTH, "3000")).andExpect(content().bytes(data)).andRespond(withSuccess());
        server.expect(ExpectedCount.once(), requestTo(HUB + "/verify")).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer hf_tok"))
                .andExpect(jsonPath("$.oid").value(oid)).andExpect(jsonPath("$.size").value(3000)).andRespond(withSuccess());
        server.expect(ExpectedCount.once(), requestTo(COMMIT)).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer hf_tok"))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, "application/x-ndjson"))
                .andExpect(content().string("{\"key\":\"header\",\"value\":{\"summary\":\"Pesi del training #7\",\"description\":\"\"}}\n"
                        + "{\"key\":\"lfsFile\",\"value\":{\"path\":\"flux-lora.safetensors\",\"algo\":\"sha256\",\"oid\":\"" + oid + "\",\"size\":3000}}\n"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.uploadWeights("hf_tok", REPO, weights, "Pesi del training #7");

        server.verify();
        assertThat(weights.reads).as("una lettura per l'impronta, una per l'invio").isEqualTo(2);
    }

    // --- per parti ------------------------------------------------------------------------------------------------

    @Test
    void aMultipartUploadSendsOnePutPerPartAndThenTheCompletion() throws Exception {
        byte[] data = randomBytes(2500);
        String oid = sha256(data);
        FakeWeights weights = new FakeWeights(data, data.length);

        server.expect(requestTo(PREUPLOAD)).andRespond(withSuccess(PREUPLOAD_LFS, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BATCH)).andRespond(withSuccess("{\"transfer\":\"multipart\",\"objects\":[{\"oid\":\"" + oid + "\",\"size\":2500,\"actions\":{"
                + "\"upload\":{\"href\":\"" + HUB + "/complete/xyz\",\"header\":{\"chunk_size\":\"1000\","
                + "\"00002\":\"http://s3.test/p2\",\"00001\":\"http://s3.test/p1\",\"00003\":\"http://s3.test/p3\"}}}}]}", LFS));
        server.expect(requestTo("http://s3.test/p1")).andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION)).andExpect(content().bytes(java.util.Arrays.copyOfRange(data, 0, 1000)))
                .andRespond(withSuccess().header(HttpHeaders.ETAG, "\"e1\""));
        server.expect(requestTo("http://s3.test/p2")).andExpect(content().bytes(java.util.Arrays.copyOfRange(data, 1000, 2000)))
                .andRespond(withSuccess().header(HttpHeaders.ETAG, "\"e2\""));
        server.expect(requestTo("http://s3.test/p3")).andExpect(content().bytes(java.util.Arrays.copyOfRange(data, 2000, 2500)))
                .andRespond(withSuccess().header(HttpHeaders.ETAG, "\"e3\""));
        server.expect(requestTo(HUB + "/complete/xyz")).andExpect(header(HttpHeaders.CONTENT_TYPE, LFS.toString())).andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andExpect(jsonPath("$.oid").value(oid))
                .andExpect(jsonPath("$.parts[0].partNumber").value(1)).andExpect(jsonPath("$.parts[0].etag").value("\"e1\""))
                .andExpect(jsonPath("$.parts[2].partNumber").value(3)).andExpect(jsonPath("$.parts[2].etag").value("\"e3\""))
                .andRespond(withSuccess());
        server.expect(requestTo(COMMIT)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.uploadWeights("hf_tok", REPO, weights, "msg");

        server.verify();
        assertThat(weights.reads).isEqualTo(2);
    }

    @Test
    void aMultipartResponseWithTheWrongNumberOfPartsIsRefusedBeforeSendingAnything() throws Exception {
        byte[] data = randomBytes(2500);
        String oid = sha256(data);

        server.expect(requestTo(PREUPLOAD)).andRespond(withSuccess(PREUPLOAD_LFS, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BATCH)).andRespond(withSuccess("{\"objects\":[{\"oid\":\"" + oid + "\",\"size\":2500,\"actions\":{\"upload\":{\"href\":\"" + HUB
                + "/complete\",\"header\":{\"chunk_size\":\"1000\",\"00001\":\"http://s3.test/p1\"}}}}]}", LFS));

        assertThatThrownBy(() -> client.uploadWeights("hf_tok", REPO, new FakeWeights(data, data.length), "m"))
                .isInstanceOfSatisfying(HuggingFaceException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.PERMANENT);
                    assertThat(e.getMessage()).isEqualTo("huggingface.error.lfsMalformed");
                });
        server.verify();
    }

    // --- gia' presente -------------------------------------------------------------------------------------------

    @Test
    void contentHuggingFaceAlreadyHasIsNotSentAgainButIsStillCommitted() throws Exception {
        byte[] data = randomBytes(1500);
        String oid = sha256(data);
        FakeWeights weights = new FakeWeights(data, data.length);

        server.expect(requestTo(PREUPLOAD)).andRespond(withSuccess(PREUPLOAD_LFS, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BATCH)).andRespond(withSuccess("{\"objects\":[{\"oid\":\"" + oid + "\",\"size\":1500}]}", LFS));
        server.expect(requestTo(COMMIT)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.uploadWeights("hf_tok", REPO, weights, "msg");

        server.verify();
        assertThat(weights.reads).as("solo l'impronta: nessun secondo download").isEqualTo(1);
    }

    // --- rifiuti e guasti ----------------------------------------------------------------------------------------

    @Test
    void aFileHuggingFaceWantsAsARegularBlobIsRefusedAndNothingElseIsCalled() throws Exception {
        byte[] data = randomBytes(100);
        server.expect(requestTo(PREUPLOAD)).andRespond(withSuccess("{\"files\":[{\"path\":\"flux-lora.safetensors\",\"uploadMode\":\"regular\",\"shouldIgnore\":false}]}",
                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.uploadWeights("hf_tok", REPO, new FakeWeights(data, data.length), "m"))
                .isInstanceOfSatisfying(HuggingFaceException.class, e -> assertThat(e.getMessage()).isEqualTo("huggingface.error.notLfs"));
        server.verify();
    }

    @Test
    void anErrorOnTheLfsObjectIsAPermanentRefusalWithTheServersReason() throws Exception {
        byte[] data = randomBytes(100);
        String oid = sha256(data);
        server.expect(requestTo(PREUPLOAD)).andRespond(withSuccess(PREUPLOAD_LFS, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BATCH)).andRespond(withSuccess("{\"objects\":[{\"oid\":\"" + oid + "\",\"size\":100,\"error\":{\"code\":403,\"message\":\"no write access\"}}]}", LFS));

        assertThatThrownBy(() -> client.uploadWeights("hf_tok", REPO, new FakeWeights(data, data.length), "m"))
                .isInstanceOfSatisfying(HuggingFaceException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.PERMANENT);
                    assertThat(e.getMessage()).isEqualTo("huggingface.error.lfsRefused");
                });
    }

    /** Un download che finisce prima del tempo non si spedisce: l'impronta sarebbe quella di un file diverso. */
    @Test
    void aTruncatedDownloadIsATransientErrorBeforeTouchingHuggingFace() {
        byte[] data = randomBytes(100);

        assertThatThrownBy(() -> client.uploadWeights("hf_tok", REPO, new FakeWeights(data, 5000), "m"))
                .isInstanceOfSatisfying(HuggingFaceException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.TRANSIENT);
                    assertThat(e.getMessage()).isEqualTo("huggingface.error.sizeMismatch");
                });
        server.verify(); // nessuna richiesta
    }

    /** Un ritentativo dopo una risposta persa farebbe un secondo commit. */
    @Test
    void theCommitAndTheTransferAreNeverRetried() throws Exception {
        byte[] data = randomBytes(100);
        String oid = sha256(data);
        server.expect(requestTo(PREUPLOAD)).andRespond(withSuccess(PREUPLOAD_LFS, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BATCH)).andRespond(withSuccess("{\"objects\":[{\"oid\":\"" + oid + "\",\"size\":100,\"actions\":{\"upload\":{\"href\":\"http://s3.test/put\"}}}]}", LFS));
        server.expect(ExpectedCount.once(), requestTo("http://s3.test/put")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.uploadWeights("hf_tok", REPO, new FakeWeights(data, data.length), "m"))
                .isInstanceOfSatisfying(HuggingFaceException.class, e -> assertThat(e.kind()).isEqualTo(Kind.TRANSIENT));
        server.verify();
    }

    @Test
    void aVerifyAddressOnAnotherHostIsRefusedAndNeverReceivesTheToken() throws Exception {
        byte[] data = randomBytes(100);
        String oid = sha256(data);
        server.expect(requestTo(PREUPLOAD)).andRespond(withSuccess(PREUPLOAD_LFS, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BATCH)).andRespond(withSuccess("{\"objects\":[{\"oid\":\"" + oid + "\",\"size\":100,\"actions\":{"
                + "\"upload\":{\"href\":\"http://s3.test/put\"},\"verify\":{\"href\":\"http://evil.test/verify\"}}}]}", LFS));
        server.expect(ExpectedCount.once(), requestTo("http://s3.test/put")).andRespond(withSuccess());
        // nessuna aspettativa su evil.test (ne' sul commit): una richiesta li' farebbe fallire il server finto

        assertThatThrownBy(() -> client.uploadWeights("hf_tok", REPO, new FakeWeights(data, data.length), "m"))
                .isInstanceOfSatisfying(HuggingFaceException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.PERMANENT);
                    assertThat(e.getMessage()).isEqualTo("huggingface.error.lfsMalformed");
                });
        server.verify();
    }

    @Test
    void aHuggingFaceErrorDuringTheTransferStaysHuggingFaceEvenInsideAnotherServicesCall() throws Exception {
        byte[] data = randomBytes(100);
        String oid = sha256(data);
        server.expect(requestTo(PREUPLOAD)).andRespond(withSuccess(PREUPLOAD_LFS, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BATCH)).andRespond(withSuccess("{\"objects\":[{\"oid\":\"" + oid + "\",\"size\":100,\"actions\":{\"upload\":{\"href\":\"http://s3.test/put\"}}}]}", LFS));
        server.expect(ExpectedCount.once(), requestTo("http://s3.test/put")).andRespond(withStatus(HttpStatus.FORBIDDEN));
        // I pesi si leggono dentro una chiamata remota di Replicate: questa riclassifica come suo ogni errore non ancora classificato.
        FakeWeights inner = new FakeWeights(data, data.length);
        WeightsFile insideReplicate = new WeightsFile() {
            @Override
            public String name() {
                return inner.name();
            }

            @Override
            public long size() {
                return inner.size();
            }

            @Override
            public <T> T read(Reader<T> reader) {
                try {
                    return inner.read(reader);
                } catch (org.hexa.core.kernel.remote.RemoteServiceException classified) {
                    throw classified;
                } catch (RuntimeException unclassified) {
                    throw new org.hexa.app.generation.domain.ReplicateException("replicate.error.httpError", unclassified);
                }
            }
        };

        assertThatThrownBy(() -> client.uploadWeights("hf_tok", REPO, insideReplicate, "m"))
                .isInstanceOfSatisfying(HuggingFaceException.class, e -> assertThat(e.source()).isEqualTo(org.hexa.app.shared.domain.AppEventSource.HUGGINGFACE));
        server.verify();
    }

    @Test
    void aMissingRepoIsAnErrorTheClientDoesNotWorkAroundByCreatingIt() throws Exception {
        byte[] data = randomBytes(100);
        server.expect(ExpectedCount.once(), requestTo(PREUPLOAD)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.uploadWeights("hf_tok", REPO, new FakeWeights(data, data.length), "m"))
                .isInstanceOfSatisfying(HuggingFaceException.class, e -> assertThat(e.kind()).isEqualTo(Kind.PERMANENT));
        server.verify();
    }

    @Test
    void aMalformedRepoIdOrFileNameIsRefusedWithoutAnyCall() {
        byte[] data = randomBytes(10);

        assertThatThrownBy(() -> client.uploadWeights("hf_tok", "norepo", new FakeWeights(data, 10), "m"))
                .isInstanceOfSatisfying(HuggingFaceException.class, e -> assertThat(e.getMessage()).isEqualTo("huggingface.error.repoId"));
        assertThatThrownBy(() -> client.uploadWeights("hf_tok", REPO, new FakeWeights("../evil\"name".getBytes(StandardCharsets.UTF_8), "../evil\"name", 12), "m"))
                .isInstanceOfSatisfying(HuggingFaceException.class, e -> assertThat(e.getMessage()).isEqualTo("huggingface.error.fileName"));
        server.verify();
    }

    @Test
    void theTokenNeverAppearsInAnErrorMessage() throws Exception {
        byte[] data = randomBytes(10);
        server.expect(requestTo(PREUPLOAD)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.uploadWeights("hf_SECRET_TOKEN", REPO, new FakeWeights(data, 10), "m"))
                .satisfies(e -> assertThat(String.valueOf(e.getMessage())).doesNotContain("hf_SECRET_TOKEN"));
    }

    /** I pesi finti: i byte in memoria, la dimensione dichiarata (anche sbagliata, per un download troncato) e quante volte si e' letto. */
    private static final class FakeWeights implements WeightsFile {

        private final byte[] data;
        private final String name;
        private final long declaredSize;
        int reads;

        FakeWeights(byte[] data, long declaredSize) {
            this(data, "flux-lora.safetensors", declaredSize);
        }

        FakeWeights(byte[] data, String name, long declaredSize) {
            this.data = data;
            this.name = name;
            this.declaredSize = declaredSize;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public long size() {
            return declaredSize;
        }

        @Override
        public <T> T read(Reader<T> reader) {
            reads++;
            try (ByteArrayInputStream in = new ByteArrayInputStream(data)) {
                return reader.read(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
