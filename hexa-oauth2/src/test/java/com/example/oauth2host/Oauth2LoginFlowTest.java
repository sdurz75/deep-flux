package com.example.oauth2host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.dual.hexa.core.config.port.in.IModuleSettings;
import org.dual.hexa.oauth2.login.port.in.IOAuthAccess;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;
import java.nio.charset.StandardCharsets;

/**
 * Il flusso vero di OIDC (authorization code + PKCE), da {@code /oauth2/start} al cookie di sessione, contro un provider finto in-process: discovery,
 * firma dell'id token con JWKS, controllo di issuer/audience/nonce di Spring Security e lista degli ammessi. Il cancello e' acceso da
 * {@code HX_OAUTH2_ENABLED} (property {@code app.oauth2.enabled}), il provider arriva dalle variabili d'ambiente (qui property): come un deployer.
 */
@SpringBootTest(properties = {
        "spring.config.import=classpath:core.yml",
        "app.secrets.expiry-check-enabled=false",
        "app.oauth2.enabled=true",
        "app.oauth2.client-id=" + FakeOidcProvider.CLIENT_ID,
        "app.oauth2.client-secret=" + FakeOidcProvider.CLIENT_SECRET,
        "app.oauth2.allowed-emails=ok@example.com",
        "app.oauth2.allowed-domains=corp.example"})
@AutoConfigureMockMvc
class Oauth2LoginFlowTest {

    static FakeOidcProvider provider;

    @BeforeAll
    static void startProvider() throws Exception {
        provider = new FakeOidcProvider();
    }

    @AfterAll
    static void stopProvider() {
        provider.close();
    }

    @DynamicPropertySource
    static void issuer(DynamicPropertyRegistry registry) throws Exception {
        if (provider == null) {
            provider = new FakeOidcProvider();
        }
        registry.add("app.oauth2.issuer-uri", provider::issuer);
    }

    @Autowired MockMvc mockMvc;
    @Autowired IOAuthAccess access;
    @Autowired IModuleSettings settings;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM oauth2_login");
        jdbc.update("DELETE FROM module_config WHERE module = 'oauth2'");
        provider.email = "ok@example.com";
        provider.emailVerified = true;
        provider.subject = "sub-1";
    }

    private void signIn(MockHttpSession session, org.springframework.test.web.servlet.ResultMatcher outcome) throws Exception {
        mockMvc.perform(get("/oauth2/start/sso").session(session)).andExpect(redirectedUrl("/oauth2/authorization/sso"));
        String location = mockMvc.perform(get("/oauth2/authorization/sso").session(session)).andExpect(status().isFound())
                .andReturn().getResponse().getRedirectedUrl();
        var query = UriComponentsBuilder.fromUriString(location).build().getQueryParams();
        assertThat(location).startsWith(provider.issuer() + "/authorize");
        assertThat(query.getFirst("code_challenge")).as("PKCE anche per un client confidenziale").isNotBlank();
        provider.nonce = UriUtils.decode(query.getFirst("nonce"), StandardCharsets.UTF_8);
        mockMvc.perform(get("/login/oauth2/code/sso").param("code", "abc").param("state", UriUtils.decode(query.getFirst("state"), StandardCharsets.UTF_8))
                .session(session)).andExpect(outcome);
    }

    @Test
    void withTheGateOnEveryKindOfRequestWithoutASessionIsRefusedTheWayTheClientCanHandle() throws Exception {
        assertThat(access.isEnabled()).isTrue();

        mockMvc.perform(get("/").accept("text/html")).andExpect(status().isFound()).andExpect(redirectedUrl("/oauth2/login"));
        mockMvc.perform(get("/data").header("HX-Request", "true").header("HX-Current-URL", "http://localhost/gallery"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("HX-Redirect", "/oauth2/login"));
        // Gia' sulla pagina di accesso: niente HX-Redirect, o ricaricherebbe all'infinito.
        mockMvc.perform(get("/data").header("HX-Request", "true").header("HX-Current-URL", "http://localhost/oauth2/login"))
                .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("HX-Redirect"));
        mockMvc.perform(get("/data").accept("text/event-stream")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/oauth2/login")).andExpect(status().isOk());
    }

    @Test
    void anAllowedEmailSignsInAndTheSessionThenOpensTheApp() throws Exception {
        MockHttpSession session = new MockHttpSession();

        signIn(session, redirectedUrl("/"));

        mockMvc.perform(get("/").accept("text/html").session(session)).andExpect(status().isOk());
        mockMvc.perform(post("/ping").session(session).header("Sec-Fetch-Site", "same-origin")).andExpect(status().isOk());
        assertThat(access.status().verifiedLogin()).isTrue();
    }

    @Test
    void anAccountTheProviderAuthenticatesButTheListDoesNotKnowGetsNoSession() throws Exception {
        provider.email = "stranger@example.com";
        MockHttpSession session = new MockHttpSession();

        signIn(session, redirectedUrl("/oauth2/login?error=denied"));

        mockMvc.perform(get("/").accept("text/html").session(session)).andExpect(status().isFound()).andExpect(redirectedUrl("/oauth2/login"));
        assertThat(access.status().verifiedLogin()).isFalse();
    }

    @Test
    void anUnverifiedEmailIsRefusedEvenIfItIsOnTheList() throws Exception {
        provider.emailVerified = false;

        signIn(new MockHttpSession(), redirectedUrl("/oauth2/login?error=denied"));
    }

    @Test
    void aWholeDomainOnTheListLetsItsMembersInButNotALookalike() throws Exception {
        provider.email = "Anyone@Corp.Example";
        signIn(new MockHttpSession(), redirectedUrl("/"));

        provider.email = "anyone@evilcorp.example";
        signIn(new MockHttpSession(), redirectedUrl("/oauth2/login?error=denied"));
    }

    @Test
    void aSavedEmailBindsToTheFirstIdentityAndRefusesAnotherAccountWithTheSameAddress() throws Exception {
        settings.save("oauth2", Map.of("enabled", "true", "allowed-emails", " Saved@Example.com "));
        provider.email = "saved@example.com";
        provider.subject = "sub-A";
        signIn(new MockHttpSession(), redirectedUrl("/"));

        provider.subject = "sub-B";
        signIn(new MockHttpSession(), redirectedUrl("/oauth2/login?error=denied"));
        provider.subject = "sub-A";
        signIn(new MockHttpSession(), redirectedUrl("/"));
    }

    @Test
    void aWriteFromAnotherSiteIsRejectedEvenWithAValidSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        signIn(session, redirectedUrl("/"));

        mockMvc.perform(post("/ping").session(session).header("Sec-Fetch-Site", "cross-site")).andExpect(status().isForbidden());
        mockMvc.perform(post("/ping").session(session).header("Origin", "https://evil.example").header("Host", "app.example")).andExpect(status().isForbidden());
        mockMvc.perform(post("/ping").session(session).header("Origin", "https://app.example").header("Host", "app.example")).andExpect(status().isOk());
    }

    @Test
    void theEnvironmentEntriesAreSummedWithTheSavedOnesAndShownReadOnly() throws Exception {
        settings.save("oauth2", Map.of("enabled", "true", "allowed-emails", "saved@example.com"));

        assertThat(settings.values("oauth2").getList("allowed-emails")).containsExactlyInAnyOrder("saved@example.com", "ok@example.com");
        assertThat(settings.formState("oauth2").environment().get("allowed-emails")).containsExactly("ok@example.com");
    }

    @Test
    void anEnvironmentEnabledGateCannotBeTurnedOffFromTheSettings() throws Exception {
        String body = mockMvc.perform(post("/settings/oauth2").header("HX-Request", "true").header("Sec-Fetch-Site", "same-origin")
                        .session(signedIn()).param("allowed-emails", "ok@example.com"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("HX_OAUTH2_ENABLED");
        assertThat(access.isEnabled()).isTrue();
    }

    private MockHttpSession signedIn() throws Exception {
        MockHttpSession session = new MockHttpSession();
        signIn(session, redirectedUrl("/"));
        return session;
    }
}
