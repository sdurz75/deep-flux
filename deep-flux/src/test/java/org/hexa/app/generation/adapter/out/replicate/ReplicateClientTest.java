package org.hexa.app.generation.adapter.out.replicate;

import java.util.Map;

import org.hexa.app.generation.domain.ReplicateException;
import org.hexa.core.kernel.i18n.Messages;
import org.hexa.core.kernel.remote.RemoteServiceException.Kind;
import org.junit.jupiter.api.Test;
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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** MockRestServiceServer: nessuna chiamata di rete verso Replicate, nessuna prediction (a pagamento) creata. */
class ReplicateClientTest {

    private static final String PREDICTION = "{\"id\":\"pred-1\",\"status\":\"processing\"}";

    private final Messages messages = mock(Messages.class);
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final ReplicateClient client;

    ReplicateClientTest() {
        when(messages.get(anyString(), any(Object[].class))).thenAnswer(i -> i.getArgument(0));
        when(messages.get(anyString())).thenAnswer(i -> i.getArgument(0));
        client = new ReplicateClient(builder, "http://replicate.test/v1", "token", messages);
    }

    /** Il vincolo che conta: un ritentativo di createPrediction potrebbe creare (e fatturare) una seconda prediction. */
    @Test
    void createPredictionIsNeverRetried() {
        server.expect(ExpectedCount.once(), method(HttpMethod.POST)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.createPrediction("owner/name", null, Map.of("prompt", "x")))
                .isInstanceOfSatisfying(ReplicateException.class, e -> assertThat(e.kind()).isEqualTo(Kind.TRANSIENT));

        server.verify(); // esattamente UNA richiesta: una seconda avrebbe fatto fallire il mock
    }

    @Test
    void getPredictionRetriesATransientErrorOnce() {
        server.expect(requestTo("http://replicate.test/v1/predictions/pred-1"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo("http://replicate.test/v1/predictions/pred-1"))
                .andRespond(withSuccess(PREDICTION, MediaType.APPLICATION_JSON));

        assertThat(client.getPrediction("pred-1").id()).isEqualTo("pred-1");
        server.verify();
    }

    @Test
    void getPredictionDoesNotRetryAPermanentError() {
        server.expect(ExpectedCount.once(), requestTo("http://replicate.test/v1/predictions/pred-1"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.getPrediction("pred-1"))
                .isInstanceOfSatisfying(ReplicateException.class, e -> assertThat(e.kind()).isEqualTo(Kind.PERMANENT));
        server.verify();
    }

    @Test
    void aMissingTokenIsAConfigurationErrorAndNeverHitsTheNetwork() {
        ReplicateClient noToken = new ReplicateClient(builder, "http://replicate.test/v1", "", messages);

        assertThatThrownBy(() -> noToken.getPrediction("pred-1"))
                .isInstanceOfSatisfying(ReplicateException.class, e -> assertThat(e.kind()).isEqualTo(Kind.CONFIGURATION));
        server.verify();
    }

    @Test
    void getModelReadsTheLatestVersionAndItsInputFields() {
        server.expect(ExpectedCount.once(), requestTo("http://replicate.test/v1/models/owner/lora"))
                .andRespond(withSuccess("{\"latest_version\":{\"id\":\"abc123\",\"openapi_schema\":{\"components\":{\"schemas\":"
                        + "{\"Input\":{\"properties\":{\"prompt\":{},\"lora_scale\":{}}}}}}}}", MediaType.APPLICATION_JSON));

        var version = client.getModel("owner/lora").flatMap(ModelResponse::toLatestVersion).orElseThrow();

        assertThat(version.id()).isEqualTo("abc123");
        assertThat(version.inputFields()).containsExactlyInAnyOrder("prompt", "lora_scale");
        server.verify();
    }

    @Test
    void getModelWithoutVersionsOrWithAnUnknownSchemaIsHandledLeniently() {
        server.expect(requestTo("http://replicate.test/v1/models/owner/new"))
                .andRespond(withSuccess("{\"latest_version\":null}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://replicate.test/v1/models/owner/bare"))
                .andRespond(withSuccess("{\"latest_version\":{\"id\":\"v1\"}}", MediaType.APPLICATION_JSON));

        assertThat(client.getModel("owner/new").flatMap(ModelResponse::toLatestVersion)).isEmpty();
        assertThat(client.getModel("owner/bare").flatMap(ModelResponse::toLatestVersion).orElseThrow().inputFields()).isEmpty();
    }

    /** Un modello inesistente non e' un errore del servizio: vuoto (niente retry, niente evento). */
    @Test
    void getModelOfAMissingModelIsEmpty() {
        server.expect(ExpectedCount.once(), requestTo("http://replicate.test/v1/models/owner/missing"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.getModel("owner/missing")).isEmpty();
        server.verify();
    }
}
