package org.dual.replicate.app.training.adapter.out.huggingface;

import org.dual.replicate.app.training.domain.HfAccount;
import org.dual.replicate.app.training.domain.HuggingFaceException;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.dual.replicate.core.kernel.remote.RemoteServiceException.Kind;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** MockRestServiceServer: nessuna chiamata a HuggingFace, nessun repo creato. */
class HuggingFaceClientTest {

    private static final String BASE = "http://hf.test/api";

    private final Messages messages = mock(Messages.class);
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final HuggingFaceClient client;

    HuggingFaceClientTest() {
        when(messages.get(anyString(), any(Object[].class))).thenAnswer(i -> i.getArgument(0));
        when(messages.get(anyString())).thenAnswer(i -> i.getArgument(0));
        client = new HuggingFaceClient(builder, BASE, messages);
    }

    @Test
    void whoamiReadsTheUserAndTheRoleOfTheToken() {
        server.expect(ExpectedCount.once(), requestTo(BASE + "/whoami-v2")).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer hf_tok"))
                .andRespond(withSuccess("{\"name\":\"sandro\",\"type\":\"user\",\"auth\":{\"type\":\"access_token\",\"accessToken\":{\"role\":\"write\"}}}",
                        MediaType.APPLICATION_JSON));

        HfAccount account = client.whoami("hf_tok");

        assertThat(account.username()).isEqualTo("sandro");
        assertThat(account.role()).isEqualTo("write");
        assertThat(account.isReadOnly()).isFalse();
        server.verify();
    }

    @Test
    void aReadTokenIsReadOnlyAndAFineGrainedOneIsLetThrough() {
        server.expect(requestTo(BASE + "/whoami-v2")).andRespond(withSuccess(
                "{\"name\":\"a\",\"auth\":{\"accessToken\":{\"role\":\"read\"}}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/whoami-v2")).andRespond(withSuccess(
                "{\"name\":\"b\",\"auth\":{\"accessToken\":{\"role\":\"fineGrained\"}}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/whoami-v2")).andRespond(withSuccess("{\"name\":\"c\"}", MediaType.APPLICATION_JSON));

        assertThat(client.whoami("t").isReadOnly()).isTrue();
        assertThat(client.whoami("t").isReadOnly()).isFalse();
        assertThat(client.whoami("t")).as("un ruolo che non si riconosce non blocca: lo decide HuggingFace al repo").isEqualTo(new HfAccount("c", null));
    }

    @Test
    void anInvalidTokenIsAPermanentErrorAndIsNotRetried() {
        server.expect(ExpectedCount.once(), requestTo(BASE + "/whoami-v2")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.whoami("sbagliato")).isInstanceOfSatisfying(HuggingFaceException.class, e -> {
            assertThat(e.kind()).isEqualTo(Kind.PERMANENT);
            assertThat(e.getMessage()).doesNotContain("sbagliato");
        });
        server.verify();
    }

    @Test
    void anAnswerWithoutAUsernameIsAnErrorWithATranslatedMessage() {
        server.expect(requestTo(BASE + "/whoami-v2")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.whoami("t")).isInstanceOfSatisfying(HuggingFaceException.class,
                e -> assertThat(e.getMessage()).isEqualTo("huggingface.error.noUsername"));
    }

    @Test
    void createModelRepoPostsAPrivateModelRepo() {
        server.expect(ExpectedCount.once(), requestTo(BASE + "/repos/create")).andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer hf_tok"))
                .andExpect(jsonPath("$.type").value("model"))
                .andExpect(jsonPath("$.name").value("il-mio-gatto"))
                .andExpect(jsonPath("$.private").value(true))
                .andRespond(withSuccess("{\"url\":\"https://huggingface.co/sandro/il-mio-gatto\"}", MediaType.APPLICATION_JSON));

        client.createModelRepo("hf_tok", "il-mio-gatto", true);

        server.verify();
    }

    @Test
    void aPublicRepoIsRequestedAsNotPrivate() {
        server.expect(requestTo(BASE + "/repos/create")).andExpect(jsonPath("$.private").value(false))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.createModelRepo("hf_tok", "x", false);

        server.verify();
    }

    /** Un repo che esiste gia' e' quello che si voleva (e un ritentativo dopo un timeout lo trova creato dal tentativo precedente). */
    @Test
    void anExistingRepoIsNotAnError() {
        server.expect(ExpectedCount.once(), requestTo(BASE + "/repos/create")).andRespond(withStatus(HttpStatus.CONFLICT));

        assertThatCode(() -> client.createModelRepo("hf_tok", "gia-esiste", true)).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void aRefusedRepoCreationIsAPermanentError() {
        server.expect(ExpectedCount.once(), requestTo(BASE + "/repos/create")).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.createModelRepo("hf_tok", "x", true))
                .isInstanceOfSatisfying(HuggingFaceException.class, e -> assertThat(e.kind()).isEqualTo(Kind.PERMANENT));
        server.verify();
    }

    @Test
    void createModelRepoRetriesATransientErrorBecauseTheCallIsIdempotent() {
        server.expect(requestTo(BASE + "/repos/create")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(BASE + "/repos/create")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.createModelRepo("hf_tok", "x", true);

        server.verify();
    }

    @Test
    void repoExistsIsTrueFor200AndFalseFor404() {
        server.expect(requestTo(BASE + "/models/sandro/c1")).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer hf_tok"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/models/sandro/c2")).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.repoExists("hf_tok", "sandro/c1")).isTrue();
        assertThat(client.repoExists("hf_tok", "sandro/c2")).isFalse();
        server.verify();
    }

    @Test
    void repoExistsOfAMalformedIdIsFalseWithoutACall() {
        assertThat(client.repoExists("hf_tok", "senza-utente")).isFalse();
        server.verify();
    }

    @Test
    void repoExistsRaisesAnAuthFailureInsteadOfPretendingTheRepoIsMissing() {
        server.expect(ExpectedCount.once(), requestTo(BASE + "/models/sandro/c1")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.repoExists("scaduto", "sandro/c1")).isInstanceOf(HuggingFaceException.class);
    }
}
