package ${package}.example.adapter.out.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ${package}.example.domain.ExampleRemoteException;
import org.hexa.core.kernel.i18n.Messages;
import org.hexa.core.kernel.remote.RemoteServiceException.Kind;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Il contratto del client remoto, senza rete: un guasto transitorio viene ritentato (e un ritentativo riuscito non lascia tracce), uno permanente no,
 * senza URL il guasto e' di configurazione. Il registro e il toast li fa il resolver del core quando l'eccezione risale al controller.
 */
class ExampleRemoteClientTest {

    private final Messages messages = messages();

    private static Messages messages() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        return new Messages(source);
    }

    @Test
    void aTransientFailureIsRetriedAndThenSucceeds() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://remote.test/status")).andRespond(withServerError());
        server.expect(requestTo("http://remote.test/status")).andRespond(withSuccess(" ok \n", org.springframework.http.MediaType.TEXT_PLAIN));

        String status = new ExampleRemoteClient(builder, "http://remote.test", messages).fetchStatus();

        assertThat(status).isEqualTo("ok");
        server.verify();
    }

    @Test
    void aPermanentFailureIsNotRetried() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://remote.test/status")).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> new ExampleRemoteClient(builder, "http://remote.test", messages).fetchStatus())
                .isInstanceOfSatisfying(ExampleRemoteException.class, e -> assertThat(e.kind()).isEqualTo(Kind.PERMANENT));
        server.verify();
    }

    @Test
    void aMissingBaseUrlIsAConfigurationFailure() {
        assertThatThrownBy(() -> new ExampleRemoteClient(RestClient.builder(), "", messages).fetchStatus())
                .isInstanceOfSatisfying(ExampleRemoteException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.CONFIGURATION);
                    assertThat(e.isReportable()).isTrue();
                });
    }
}
