package org.dual.replicate.app.credits.adapter.out.openrouter;

import java.math.BigDecimal;

import org.dual.replicate.core.ai.domain.OpenRouterException;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.dual.replicate.core.kernel.remote.RemoteServiceException.Kind;
import org.junit.jupiter.api.Test;
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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** MockRestServiceServer: nessuna chiamata di rete verso OpenRouter. */
class OpenRouterCreditsClientTest {

    private final Messages messages = mock(Messages.class);
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    OpenRouterCreditsClientTest() {
        when(messages.get(anyString(), any(Object[].class))).thenAnswer(i -> i.getArgument(0));
        when(messages.get(anyString())).thenAnswer(i -> i.getArgument(0));
    }

    private OpenRouterCreditsClient client(String key) {
        return new OpenRouterCreditsClient(builder, "http://openrouter.test/api/v1", key, messages);
    }

    @Test
    void remainingIsPurchasedMinusUsedAndSendsTheManagementKey() {
        server.expect(requestTo("http://openrouter.test/api/v1/credits"))
                .andExpect(header("Authorization", "Bearer sk-mgmt"))
                .andRespond(withSuccess("{\"data\":{\"total_credits\":100.5,\"total_usage\":25.75,\"extra\":1}}", MediaType.APPLICATION_JSON));

        assertThat(client("sk-mgmt").remaining()).isEqualByComparingTo(new BigDecimal("74.75"));
        server.verify();
    }

    @Test
    void withoutManagementKeyItIsNotConfiguredAndNeverCallsTheService() {
        OpenRouterCreditsClient client = client("  ");

        assertThat(client.isConfigured()).isFalse();
        assertThatThrownBy(client::remaining)
                .isInstanceOfSatisfying(OpenRouterException.class, e -> assertThat(e.kind()).isEqualTo(Kind.CONFIGURATION));
        server.verify(); // nessuna richiesta attesa, nessuna ricevuta
    }

    @Test
    void aForbiddenKeyIsAPermanentError() {
        server.expect(requestTo("http://openrouter.test/api/v1/credits")).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client("sk-chat").remaining())
                .isInstanceOfSatisfying(OpenRouterException.class, e -> assertThat(e.kind()).isEqualTo(Kind.PERMANENT));
    }

    @Test
    void anUnreadableBodyIsAPermanentError() {
        server.expect(requestTo("http://openrouter.test/api/v1/credits")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client("sk-mgmt").remaining())
                .isInstanceOfSatisfying(OpenRouterException.class, e -> assertThat(e.kind()).isEqualTo(Kind.PERMANENT));
    }
}
