package org.dual.hexa.core.kernel.remote;

import java.time.Duration;
import java.util.Map;

import org.dual.hexa.core.events.domain.CoreEventSource;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.dual.hexa.core.kernel.remote.RemoteServiceException.Kind;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

/**
 * Dimostra la checklist di CLAUDE.md ("nuovo servizio remoto"): un servizio completo (eccezione + client) e' questo, e
 * la classificazione/retry HTTP arrivano gratis da {@link RestRemoteClient}.
 */
class RestRemoteClientTest {

    static class FooException extends RemoteServiceException {
        FooException(String message, Throwable cause, Kind kind) {
            super(CoreEventSource.INTERNAL, kind, message, cause);
        }
    }

    static class FooClient extends RestRemoteClient {
        private final RestClient http;

        FooClient(RestClient.Builder builder, Messages messages) {
            super("foo", messages, FooException::new, RetryPolicy.of(2, Duration.ZERO));
            this.http = builder.baseUrl("http://foo.test").build();
        }

        Map<?, ?> read() {
            return remote.call("read", () -> http.get().uri("/thing").retrieve().body(Map.class));
        }

        Map<?, ?> create() {
            return remote.call("create", RetryPolicy.NONE, () -> http.post().uri("/thing").retrieve().body(Map.class));
        }
    }

    private final Messages messages = mock(Messages.class);
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final FooClient client;

    RestRemoteClientTest() {
        when(messages.get(anyString(), any(Object[].class))).thenAnswer(i -> i.getArgument(0));
        client = new FooClient(builder, messages);
    }

    @Test
    void a503IsRetriedAndThenSucceeds() {
        server.expect(requestTo("http://foo.test/thing")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo("http://foo.test/thing")).andRespond(withSuccess("{\"a\":1}", MediaType.APPLICATION_JSON));

        assertThat(client.read().get("a")).isEqualTo(1);
        server.verify();
    }

    @Test
    void a401IsPermanentAndNotRetried() {
        server.expect(requestTo("http://foo.test/thing")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(client::read).isInstanceOfSatisfying(FooException.class, e -> {
            assertThat(e.kind()).isEqualTo(Kind.PERMANENT);
            assertThat(e.getMessage()).isEqualTo("foo.error.httpError");
        });
        server.verify();
    }

    @Test
    void anExplicitNonePolicyDoesNotRetryATransientError() {
        server.expect(method(HttpMethod.POST)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(client::create).isInstanceOfSatisfying(FooException.class,
                e -> assertThat(e.kind()).isEqualTo(Kind.TRANSIENT));
        server.verify();
    }

    @Test
    void statusCodesAreClassifiedInOnePlace() {
        assertThat(RestClientTranslator.kindOfStatus(408)).isEqualTo(Kind.TRANSIENT);
        assertThat(RestClientTranslator.kindOfStatus(429)).isEqualTo(Kind.TRANSIENT);
        assertThat(RestClientTranslator.kindOfStatus(502)).isEqualTo(Kind.TRANSIENT);
        assertThat(RestClientTranslator.kindOfStatus(507)).isEqualTo(Kind.PERMANENT);
        assertThat(RestClientTranslator.kindOfStatus(403)).isEqualTo(Kind.PERMANENT);
        assertThat(RestClientTranslator.kindOfStatus(404)).isEqualTo(Kind.PERMANENT);
    }
}
