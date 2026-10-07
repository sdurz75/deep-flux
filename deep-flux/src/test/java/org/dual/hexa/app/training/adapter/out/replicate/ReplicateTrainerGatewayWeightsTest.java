package org.dual.hexa.app.training.adapter.out.replicate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.dual.hexa.app.generation.domain.ReplicateException;
import org.dual.hexa.app.training.domain.WeightsFile;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.dual.hexa.core.kernel.remote.RemoteServiceException.Kind;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Lettura dello schema del trainer e dei pesi di un training riuscito, contro un server finto: nessuna chiamata a Replicate, nessun download vero. Le forme sono
 * quelle lette da un training vero ({@code output.weights} = un {@code flux-lora.tar} su replicate.delivery con {@code flux-lora/flux-lora.safetensors}).
 */
class ReplicateTrainerGatewayWeightsTest {

    private static final String BASE = "http://replicate.test/v1";
    private static final String TAR_URL = "http://cdn.test/xezq/abc/flux-lora.tar";
    private static final byte[] WEIGHTS = "LORA-WEIGHTS".repeat(500).getBytes(StandardCharsets.UTF_8);

    private final Messages messages = mock(Messages.class);
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final ReplicateTrainerGateway gateway;

    ReplicateTrainerGatewayWeightsTest() {
        when(messages.get(anyString(), any(Object[].class))).thenAnswer(i -> i.getArgument(0) + " "
                + Arrays.stream(i.getArguments()).skip(1).map(String::valueOf).collect(Collectors.joining(",")));
        when(messages.get(anyString())).thenAnswer(i -> i.getArgument(0));
        gateway = new ReplicateTrainerGateway(builder, BASE, "r8_token", messages, "replicate/fast-flux-trainer", "", "gpu-t4");
    }

    /** Un server che rispetta il Range come il CDN vero (206 + Content-Range) sul tar dato; registra gli intervalli chiesti. */
    private final java.util.List<String> ranges = new java.util.ArrayList<>();

    private ResponseCreator rangeResponder(byte[] archive) {
        return request -> {
            String range = request.getHeaders().getFirst(HttpHeaders.RANGE);
            ranges.add(range);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("bytes=(\\d+)-(\\d+)").matcher(String.valueOf(range));
            if (!m.matches()) {
                return new MockClientHttpResponse(archive, HttpStatus.OK); // un server che ignora il Range
            }
            int from = Integer.parseInt(m.group(1));
            int to = Math.min(Integer.parseInt(m.group(2)), archive.length - 1);
            MockClientHttpResponse response = new MockClientHttpResponse(Arrays.copyOfRange(archive, from, to + 1), HttpStatus.PARTIAL_CONTENT);
            response.getHeaders().set(HttpHeaders.CONTENT_RANGE, "bytes " + from + "-" + to + "/" + archive.length);
            return response;
        };
    }

    private static byte[] tar() {
        return new TarBuilder().directory("flux-lora/").file("flux-lora/flux-lora.safetensors", WEIGHTS).build();
    }

    private static String outputOf(String url) {
        // Come la risposta vera: l'input porta il token HuggingFace, che il gateway non deve leggere.
        return "{\"id\":\"t1\",\"status\":\"succeeded\",\"input\":{\"hf_token\":\"hf_SECRET\",\"hf_repo_id\":\"u/r\"},\"output\":{\"version\":\"o/m:abc\",\"weights\":"
                + (url == null ? "null" : "\"" + url + "\"") + ",\"validation_images\":[]}}";
    }

    // --- schema del trainer --------------------------------------------------------------------------------------

    @Test
    void theInputFieldsOfAVersionComeFromItsOpenApiSchema() {
        server.expect(ExpectedCount.once(), requestTo(BASE + "/models/replicate/fast-flux-trainer/versions/v1"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer r8_token"))
                .andRespond(withSuccess("{\"id\":\"v1\",\"openapi_schema\":{\"components\":{\"schemas\":{\"TrainingInput\":{\"properties\":"
                        + "{\"input_images\":{},\"trigger_word\":{},\"hf_repo_id\":{},\"hf_token\":{}}}}}}}", MediaType.APPLICATION_JSON));

        assertThat(gateway.trainerInputFields("v1")).contains(Set.of("input_images", "trigger_word", "hf_repo_id", "hf_token"));
        server.verify();
    }

    @Test
    void anUnexpectedSchemaShapeIsUnknownNotEmpty() {
        server.expect(ExpectedCount.once(), requestTo(BASE + "/models/replicate/fast-flux-trainer/versions/v1"))
                .andRespond(withSuccess("{\"id\":\"v1\",\"openapi_schema\":{\"components\":{}}}", MediaType.APPLICATION_JSON));

        assertThat(gateway.trainerInputFields("v1")).isEmpty();
    }

    @Test
    void theVersionThatReplicateIsRunningComesFromTheTrainingResponseNotFromTheRequest() {
        server.expect(ExpectedCount.once(), requestTo(BASE + "/trainings/t1")).andRespond(withSuccess(
                "{\"id\":\"t1\",\"status\":\"processing\",\"version\":\"56cb4a64\",\"input\":{\"hf_token\":\"hf_SECRET\"}}", MediaType.APPLICATION_JSON));

        assertThat(gateway.getTraining("t1").version()).isEqualTo("56cb4a64");
    }

    // --- pesi ----------------------------------------------------------------------------------------------------

    @Test
    void theWeightsAreFoundWithTwoSmallRangeReadsAndThenReadWithOneRangeOfExactlyTheFile() throws Exception {
        server.expect(ExpectedCount.once(), requestTo(BASE + "/trainings/t1")).andRespond(withSuccess(outputOf(TAR_URL), MediaType.APPLICATION_JSON));
        // Il download e' di un indirizzo pubblico di un altro host: il token di Replicate non ci deve andare.
        server.expect(ExpectedCount.manyTimes(), requestTo(TAR_URL)).andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION)).andRespond(rangeResponder(tar()));

        Optional<WeightsFile> found = gateway.weights("t1");

        assertThat(found).isPresent();
        WeightsFile weights = found.get();
        assertThat(weights.name()).isEqualTo("flux-lora.safetensors");
        assertThat(weights.size()).isEqualTo(WEIGHTS.length);
        assertThat(ranges).as("la cartella e il file: due intestazioni da 512 byte, nessun contenuto").containsExactly("bytes=0-511", "bytes=512-1023");

        byte[] read = weights.read(InputStream::readAllBytes);

        assertThat(read).isEqualTo(WEIGHTS);
        assertThat(ranges.get(2)).as("la lettura vera chiede solo i byte del file").isEqualTo("bytes=1024-" + (1024 + WEIGHTS.length - 1));
    }

    /** Un 200 al posto del 206 comincerebbe dal primo byte dell'archivio: i pesi sarebbero corrotti, quindi e' un errore e non un file sbagliato. */
    @Test
    void aServerThatIgnoresTheRangeIsAnErrorNotACorruptedFile() {
        server.expect(ExpectedCount.once(), requestTo(BASE + "/trainings/t1")).andRespond(withSuccess(outputOf(TAR_URL), MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.manyTimes(), requestTo(TAR_URL)).andRespond(request -> new MockClientHttpResponse(tar(), HttpStatus.OK));

        assertThatThrownBy(() -> gateway.weights("t1")).isInstanceOfSatisfying(ReplicateException.class, e -> {
            assertThat(e.kind()).isEqualTo(Kind.PERMANENT);
            assertThat(e.getMessage()).startsWith("replicate.error.rangeUnsupported");
        });
    }

    @Test
    void aTrainingWithoutAWeightsUrlOrWithoutASafetensorsFileHasNoWeights() {
        server.expect(ExpectedCount.once(), requestTo(BASE + "/trainings/t1")).andRespond(withSuccess(outputOf(null), MediaType.APPLICATION_JSON));
        assertThat(gateway.weights("t1")).isEmpty();

        server.reset();
        server.expect(ExpectedCount.once(), requestTo(BASE + "/trainings/t2")).andRespond(withSuccess(outputOf(TAR_URL), MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.manyTimes(), requestTo(TAR_URL))
                .andRespond(rangeResponder(new TarBuilder().file("a/readme.txt", "x".getBytes(StandardCharsets.UTF_8)).build()));
        assertThat(gateway.weights("t2")).isEmpty();
    }

    @Test
    void aWeightsUrlThatIsNotHttpIsRefusedBeforeAnyDownload() {
        server.expect(ExpectedCount.once(), requestTo(BASE + "/trainings/t1")).andRespond(withSuccess(outputOf("file:///etc/passwd"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.weights("t1")).isInstanceOfSatisfying(ReplicateException.class, e -> {
            assertThat(e.kind()).isEqualTo(Kind.REJECTED);
            assertThat(e.getMessage()).startsWith("replicate.error.weightsUrl");
        });
        server.verify();
    }

    @Test
    void anExpiredDownloadIsAnErrorThatTheCallerSeesNotAnEmptyResult() {
        server.expect(ExpectedCount.once(), requestTo(BASE + "/trainings/t1")).andRespond(withSuccess(outputOf(TAR_URL), MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(TAR_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> gateway.weights("t1")).isInstanceOfSatisfying(ReplicateException.class, e -> assertThat(e.kind()).isEqualTo(Kind.PERMANENT));
    }

    @Test
    void theHuggingFaceTokenOfTheTrainingInputNeverReachesAnErrorMessage() {
        server.expect(ExpectedCount.once(), requestTo(BASE + "/trainings/t1")).andRespond(withSuccess(outputOf(TAR_URL), MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(TAR_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        try {
            gateway.weights("t1");
        } catch (RuntimeException e) {
            thrown.set(e);
        }
        assertThat(thrown.get()).isNotNull();
        assertThat(String.valueOf(thrown.get().getMessage())).doesNotContain("hf_SECRET");
    }
}
