package org.dual.hexa.app.training.adapter.out.replicate;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.dual.hexa.app.generation.domain.ReplicateException;
import org.dual.hexa.app.training.domain.DatasetArchive;
import org.dual.hexa.app.training.domain.TrainerJob;
import org.dual.hexa.app.training.domain.TrainingStatus;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.dual.hexa.core.kernel.remote.RemoteServiceException.Kind;
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
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * MockRestServiceServer: nessuna chiamata di rete verso Replicate, nessun training (a pagamento), nessun modello e nessun file creati. Le forme delle richieste
 * qui sono quelle che il codice SI ASPETTA dall'API di Replicate (da verificare alla prima esecuzione reale, con permesso): i test provano che le rispetta, non
 * che Replicate le accetti.
 */
class ReplicateTrainerGatewayTest {

    private static final String BASE = "http://replicate.test/v1";
    private static final String HF_SECRET = "hf_SUPER_SECRET_value";
    private static final String TRAINING = "{\"id\":\"train-1\",\"status\":\"starting\"}";

    private final Messages messages = mock(Messages.class);
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final ReplicateTrainerGateway gateway;

    ReplicateTrainerGatewayTest() {
        // Il messaggio riporta gli argomenti (stato e corpo della risposta): senza, non si potrebbe provare che un segreto non vi finisce.
        when(messages.get(anyString(), any(Object[].class))).thenAnswer(i -> i.getArgument(0) + " "
                + Arrays.stream(i.getArguments()).skip(1).map(String::valueOf).collect(Collectors.joining(",")));
        when(messages.get(anyString())).thenAnswer(i -> i.getArgument(0));
        gateway = new ReplicateTrainerGateway(builder, BASE, "r8_token", messages, "replicate/fast-flux-trainer", "", "gpu-t4");
    }

    // --- creazione del training: a pagamento ---------------------------------------------------------------------

    /** Il vincolo che conta: un ritentativo di createTraining potrebbe avviare (e fatturare) un secondo training. */
    @Test
    void createTrainingIsNeverRetried() {
        server.expect(ExpectedCount.once(), method(HttpMethod.POST)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> gateway.createTraining("v1", "acct/model", Map.of("trigger_word", "TOK")))
                .isInstanceOfSatisfying(ReplicateException.class, e -> assertThat(e.kind()).isEqualTo(Kind.TRANSIENT));

        server.verify(); // esattamente UNA richiesta: una seconda avrebbe fatto fallire il mock
    }

    @Test
    void createTrainingPostsTheDestinationAndTheInputToTheVersionOfTheTrainer() {
        server.expect(ExpectedCount.once(), requestTo(BASE + "/models/replicate/fast-flux-trainer/versions/v1/trainings"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer r8_token"))
                .andExpect(jsonPath("$.destination").value("acct/model"))
                .andExpect(jsonPath("$.input.trigger_word").value("TOK"))
                .andExpect(jsonPath("$.input.training_steps").value(1000))
                .andExpect(jsonPath("$.input.hf_token").value(HF_SECRET))
                .andRespond(withSuccess(TRAINING, MediaType.APPLICATION_JSON));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("input_images", "https://api.replicate.com/v1/files/abc");
        input.put("trigger_word", "TOK");
        input.put("training_steps", 1000);
        input.put("hf_token", HF_SECRET);

        TrainerJob job = gateway.createTraining("v1", "acct/model", input);

        assertThat(job.externalId()).isEqualTo("train-1");
        assertThat(job.status()).isEqualTo(TrainingStatus.PENDING);
        server.verify();
    }

    /** Il corpo di un errore puo' riportare l'input della richiesta, e nell'input c'e' il token HuggingFace. */
    @Test
    void theHuggingFaceTokenNeverSurvivesInAFailedCreation() {
        String echoed = "{\"detail\":\"invalid input\",\"input\":{\"hf_token\":\"" + HF_SECRET + "\"}}";
        server.expect(ExpectedCount.once(), method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).body(echoed).contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.createTraining("v1", "acct/model", Map.of("hf_token", HF_SECRET, "trigger_word", "TOK")))
                .isInstanceOfSatisfying(ReplicateException.class, e -> {
                    assertThat(e.getMessage()).contains("invalid input").doesNotContain(HF_SECRET);
                    assertThat(e.getCause()).as("la causa riporta il corpo (e il token) nel proprio messaggio").isNull();
                    assertThat(e.kind()).as("la classificazione si conserva").isEqualTo(Kind.PERMANENT);
                });
    }

    @Test
    void aCreationErrorWithoutAHuggingFaceTokenKeepsItsCause() {
        server.expect(ExpectedCount.once(), method(HttpMethod.POST)).andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED));

        assertThatThrownBy(() -> gateway.createTraining("v1", "acct/model", Map.of("trigger_word", "TOK")))
                .isInstanceOfSatisfying(ReplicateException.class, e -> assertThat(e.getCause()).isNotNull());
    }

    // --- stato, annullamento -------------------------------------------------------------------------------------

    @Test
    void getTrainingMapsTheStatusTheErrorTheLogsAndTheComputeTime() {
        server.expect(requestTo(BASE + "/trainings/train-1")).andRespond(withSuccess(
                "{\"id\":\"train-1\",\"status\":\"succeeded\",\"logs\":\"step 1000\",\"error\":null,\"metrics\":{\"predict_time\":512.5},"
                        + "\"input\":{\"hf_token\":\"" + HF_SECRET + "\"},\"output\":{\"weights\":\"https://x\"}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/trainings/train-2")).andRespond(withSuccess(
                "{\"id\":\"train-2\",\"status\":\"failed\",\"error\":\"CUDA out of memory\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/trainings/train-3")).andRespond(withSuccess("{\"id\":\"train-3\",\"status\":\"canceled\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/trainings/train-4")).andRespond(withSuccess("{\"id\":\"train-4\",\"status\":\"processing\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/trainings/train-5")).andRespond(withSuccess("{\"id\":\"train-5\",\"status\":\"una-parola-nuova\"}", MediaType.APPLICATION_JSON));

        TrainerJob done = gateway.getTraining("train-1");
        assertThat(done.status()).isEqualTo(TrainingStatus.SUCCEEDED);
        assertThat(done.logs()).isEqualTo("step 1000");
        assertThat(done.predictTimeSeconds()).isEqualTo(512.5);
        assertThat(done.error()).isNull();
        assertThat(done.toString()).as("l'input della risposta non si legge mai").doesNotContain(HF_SECRET);

        TrainerJob failed = gateway.getTraining("train-2");
        assertThat(failed.status()).isEqualTo(TrainingStatus.FAILED);
        assertThat(failed.error()).isEqualTo("CUDA out of memory");
        assertThat(gateway.getTraining("train-3").status()).isEqualTo(TrainingStatus.CANCELED);
        assertThat(gateway.getTraining("train-4").status()).isEqualTo(TrainingStatus.PROCESSING);
        assertThat(gateway.getTraining("train-5").status()).as("una parola mai vista non chiude una riga").isEqualTo(TrainingStatus.PROCESSING);
        server.verify();
    }

    @Test
    void getTrainingRetriesATransientErrorOnceButNotAPermanentOne() {
        server.expect(requestTo(BASE + "/trainings/train-1")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(BASE + "/trainings/train-1")).andRespond(withSuccess(TRAINING, MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(BASE + "/trainings/train-2")).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(gateway.getTraining("train-1").externalId()).isEqualTo("train-1");
        assertThatThrownBy(() -> gateway.getTraining("train-2"))
                .isInstanceOfSatisfying(ReplicateException.class, e -> assertThat(e.kind()).isEqualTo(Kind.PERMANENT));
        server.verify();
    }

    @Test
    void cancelTrainingPostsToTheCancelEndpointAndReturnsTheRealState() {
        server.expect(ExpectedCount.once(), requestTo(BASE + "/trainings/train-1/cancel")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"id\":\"train-1\",\"status\":\"succeeded\"}", MediaType.APPLICATION_JSON));

        assertThat(gateway.cancelTraining("train-1").status()).isEqualTo(TrainingStatus.SUCCEEDED);
        server.verify();
    }

    @Test
    void cancellingAGoneOrAlreadyFinishedTrainingIsAnExpectedRejectionNotAFailure() {
        server.expect(ExpectedCount.once(), requestTo(BASE + "/trainings/gone/cancel")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(ExpectedCount.once(), requestTo(BASE + "/trainings/done/cancel")).andRespond(withStatus(HttpStatus.CONFLICT));

        for (String id : new String[] {"gone", "done"}) {
            assertThatThrownBy(() -> gateway.cancelTraining(id))
                    .isInstanceOfSatisfying(ReplicateException.class, e -> assertThat(e.isReportable()).isFalse());
        }
        server.verify();
    }

    @Test
    void aRejectedTokenWhileCancellingStaysAReportableFailure() {
        server.expect(ExpectedCount.once(), requestTo(BASE + "/trainings/train-1/cancel")).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> gateway.cancelTraining("train-1"))
                .isInstanceOfSatisfying(ReplicateException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.PERMANENT);
                    assertThat(e.isReportable()).isTrue();
                });
        server.verify();
    }

    // --- file ---------------------------------------------------------------------------------------------------

    @Test
    void uploadFileSendsTheZipAsAMultipartContentPartAndReturnsTheUrl() {
        server.expect(ExpectedCount.once(), requestTo(BASE + "/files")).andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer r8_token"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
                .andExpect(content().string(containsString("name=\"content\"")))
                .andExpect(content().string(containsString("filename=\"dataset.zip\"")))
                .andExpect(content().string(containsString("PK-ZIP-BYTES")))
                .andRespond(withSuccess("{\"id\":\"f1\",\"urls\":{\"get\":\"https://api.replicate.com/v1/files/f1\"}}", MediaType.APPLICATION_JSON));

        assertThat(gateway.uploadFile("dataset.zip", new BytesArchive("PK-ZIP-BYTES"))).isEqualTo("https://api.replicate.com/v1/files/f1");
        server.verify();
    }

    @Test
    void aRetriedUploadRereadsTheZipFromTheStart() {
        server.expect(requestTo(BASE + "/files")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(requestTo(BASE + "/files")).andExpect(content().string(containsString("PK-ZIP-BYTES")))
                .andRespond(withSuccess("{\"urls\":{\"get\":\"https://x/f\"}}", MediaType.APPLICATION_JSON));
        BytesArchive archive = new BytesArchive("PK-ZIP-BYTES");

        assertThat(gateway.uploadFile("dataset.zip", archive)).isEqualTo("https://x/f");

        assertThat(archive.opened.get()).as("un nuovo stream a ogni tentativo").isGreaterThanOrEqualTo(2);
        server.verify();
    }

    /** Una risposta vuota si tratta come transitoria (come nel client delle generazioni): il caricamento e' idempotente, quindi si ritenta, poi fallisce. */
    @Test
    void anUploadAnswerWithoutAnUrlIsRetriedAndThenAnError() {
        server.expect(ExpectedCount.times(3), requestTo(BASE + "/files")).andRespond(withSuccess("{\"id\":\"f1\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.uploadFile("dataset.zip", new BytesArchive("x")))
                .isInstanceOfSatisfying(ReplicateException.class, e -> assertThat(e.kind()).isEqualTo(Kind.TRANSIENT));
        server.verify();
    }

    /** ...ma la creazione del training non si ritenta MAI, nemmeno con una risposta vuota: il training potrebbe essere partito. */
    @Test
    void aTrainingAnswerWithAnEmptyBodyIsNotRetriedEither() {
        server.expect(ExpectedCount.once(), method(HttpMethod.POST)).andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.createTraining("v1", "acct/model", Map.of("trigger_word", "TOK"))).isInstanceOf(ReplicateException.class);
        server.verify();
    }

    // --- modello di destinazione --------------------------------------------------------------------------------

    @Test
    void ensureDestinationCreatesAPrivateModelWhenItIsMissing() {
        server.expect(ExpectedCount.once(), requestTo(BASE + "/account")).andRespond(withSuccess("{\"username\":\"acct\"}", MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(BASE + "/models/acct/il-mio-gatto-1")).andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(ExpectedCount.once(), requestTo(BASE + "/models")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.owner").value("acct"))
                .andExpect(jsonPath("$.name").value("il-mio-gatto-1"))
                .andExpect(jsonPath("$.visibility").value("private"))
                .andExpect(jsonPath("$.hardware").value("gpu-t4"))
                .andRespond(withStatus(HttpStatus.CREATED).body("{}").contentType(MediaType.APPLICATION_JSON));

        assertThat(gateway.ensureDestination("il-mio-gatto-1")).isEqualTo("acct/il-mio-gatto-1");
        server.verify();
    }

    @Test
    void ensureDestinationLeavesAnExistingModelAlone() {
        server.expect(requestTo(BASE + "/account")).andRespond(withSuccess("{\"username\":\"acct\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/models/acct/gia-esiste")).andRespond(withSuccess("{\"name\":\"gia-esiste\"}", MediaType.APPLICATION_JSON));

        assertThat(gateway.ensureDestination("gia-esiste")).isEqualTo("acct/gia-esiste");
        server.verify(); // nessun POST /models atteso: uno in piu' avrebbe fatto fallire il mock
    }

    /** Scrive sull'account: un ritentativo dopo un timeout troverebbe il modello gia' creato e fallirebbe per un motivo fuorviante. */
    @Test
    void creatingTheDestinationIsNeverRetried() {
        server.expect(requestTo(BASE + "/account")).andRespond(withSuccess("{\"username\":\"acct\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/models/acct/nuovo")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(ExpectedCount.once(), requestTo(BASE + "/models")).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> gateway.ensureDestination("nuovo"))
                .isInstanceOfSatisfying(ReplicateException.class, e -> assertThat(e.kind()).isEqualTo(Kind.TRANSIENT));
        server.verify();
    }

    @Test
    void anAccountWithoutAUsernameIsAnError() {
        server.expect(requestTo(BASE + "/account")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.ensureDestination("nuovo")).isInstanceOf(ReplicateException.class);
    }

    // --- versione del trainer -----------------------------------------------------------------------------------

    @Test
    void trainerVersionReadsTheLatestVersionOfTheTrainerModel() {
        server.expect(ExpectedCount.once(), requestTo(BASE + "/models/replicate/fast-flux-trainer"))
                .andRespond(withSuccess("{\"latest_version\":{\"id\":\"e5a5bc82\"}}", MediaType.APPLICATION_JSON));

        assertThat(gateway.trainerVersion()).isEqualTo("e5a5bc82");
        server.verify();
    }

    @Test
    void aConfiguredTrainerVersionIsUsedWithoutAnyCall() {
        ReplicateTrainerGateway pinned = new ReplicateTrainerGateway(builder, BASE, "r8_token", messages, "replicate/fast-flux-trainer", " pinned-1 ", "gpu-t4");

        assertThat(pinned.trainerVersion()).isEqualTo("pinned-1");
        server.verify();
    }

    @Test
    void aTrainerModelWithoutVersionsIsAPermanentError() {
        server.expect(requestTo(BASE + "/models/replicate/fast-flux-trainer")).andRespond(withSuccess("{\"latest_version\":null}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.trainerVersion())
                .isInstanceOfSatisfying(ReplicateException.class, e -> assertThat(e.kind()).isEqualTo(Kind.PERMANENT));
    }

    // --- configurazione -----------------------------------------------------------------------------------------

    @Test
    void aMissingTokenIsAConfigurationErrorOnEveryOperationAndNeverHitsTheNetwork() {
        ReplicateTrainerGateway noToken = new ReplicateTrainerGateway(builder, BASE, "", messages, "replicate/fast-flux-trainer", "", "gpu-t4");

        for (Runnable call : new Runnable[] {() -> noToken.getTraining("t"), () -> noToken.cancelTraining("t"), () -> noToken.trainerVersion(),
                () -> noToken.ensureDestination("n"), () -> noToken.createTraining("v", "a/b", Map.of()),
                () -> noToken.uploadFile("f", new BytesArchive("x"))}) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(ReplicateException.class, e -> assertThat(e.kind()).isEqualTo(Kind.CONFIGURATION));
        }
        server.verify();
    }

    /** Uno zip fatto di byte noti, che conta quante volte lo si apre. */
    private static final class BytesArchive implements DatasetArchive {

        private final byte[] bytes;
        final AtomicInteger opened = new AtomicInteger();

        BytesArchive(String text) {
            this.bytes = text.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public long size() {
            return bytes.length;
        }

        @Override
        public InputStream open() {
            opened.incrementAndGet();
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public void close() {
            // niente da eliminare
        }
    }
}
