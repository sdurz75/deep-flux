package ${package}.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.InputStream;
import java.util.Properties;
import org.dual.hexa.core.events.domain.CoreEventSource;
import org.dual.hexa.core.events.domain.EventLink;
import org.dual.hexa.core.events.port.in.ISystemEvents;
import org.dual.hexa.core.tokens.port.in.IApiTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** Le pagine rendono col layout del core: sidebar con le voci dell'app, selettore tema, breadcrumbs, fragment htmx. */
@SpringBootTest
@AutoConfigureMockMvc
class PagesRenderingTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void homeUsesTheSidebarLayoutWithThemeSwitch() throws Exception {
        String body = mockMvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("<aside", "href=\"/example\"", "data-theme-value=\"dark\"", "${appName}");
    }

#if( $usePwa == "true" )
    /** hexa-pwa: ogni pagina dichiara il manifest (col nome dell'app) e registra il service worker; il worker non e' in cache. */
    @Test
    void theAppIsInstallable() throws Exception {
        String body = mockMvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("rel=\"manifest\" href=\"/manifest.webmanifest\"", "data-sw=\"/sw.js\"");

        assertThat(mockMvc.perform(get("/manifest.webmanifest")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .contains("\"name\":\"${appName}\"");
        assertThat(mockMvc.perform(get("/sw.js")).andExpect(status().isOk()).andReturn().getResponse().getHeader("Cache-Control")).contains("no-cache");
    }

#end
    /** Il blocco con PIN e' del core: senza PIN l'app e' libera e la pagina Sicurezza sta nel menu Gestione. */
    @Test
    void theSecurityPageIsInTheManageMenuAndTheAppIsFreeWithoutAPin() throws Exception {
        String page = mockMvc.perform(get("/security")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(page).contains("Sicurezza", "action=\"/security/pin\"").doesNotContain("id=\"lock-idle\"");
        assertThat(mockMvc.perform(get("/")).andReturn().getResponse().getContentAsString()).contains("href=\"/security\"");
    }

    @Test
    @Transactional
    void theExamplePageListsAndAddsThroughHtmx() throws Exception {
        String page = mockMvc.perform(get("/example")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("aria-label=\"Percorso\"", "id=\"example-list\"");

        String fragment = mockMvc.perform(post("/example").header("HX-Request", "true").param("title", "ciao"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(fragment).contains("ciao").doesNotContain("<aside");
    }

    @Test
    @Transactional
    void aBlankTitleShowsAnInlineError() throws Exception {
        String fragment = mockMvc.perform(post("/example").header("HX-Request", "true").param("title", " "))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(fragment).contains("obbligatorio");
    }

    /**
     * Un guasto del servizio esterno (qui: URL non configurato) risale al resolver del core: 502, evento nel registro e toast nell'header htmx.
     * Il target non viene sostituito (htmx ignora le risposte di errore), quindi lo stato precedente resta.
     */
    @Test
    void aRemoteFailureIsRecordedAndShownAsAToast() throws Exception {
        var response = mockMvc.perform(post("/example/remote-check").header("HX-Request", "true"))
                .andExpect(status().isBadGateway()).andReturn().getResponse();

        assertThat(response.getHeader("HX-Trigger")).contains("system-toast").contains("EXAMPLE_REMOTE_BASE_URL");
    }

    /** 1x1 px, PNG vero: lo storage riconosce il tipo dai magic bytes, mai dal nome o dal content-type. */
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");

    @Autowired
    private ISystemEvents systemEvents;

    @Autowired
    private IApiTokens apiTokens;

    /** Upload multipart -> file nello storage servito da /images/** (core); la cancellazione toglie anche il file. */
    @Test
    @Transactional
    void anAttachmentIsStoredServedAndRemovedWithItsItem() throws Exception {
        String fragment = mockMvc.perform(multipart("/example").file(new MockMultipartFile("attachment", "a.png", "image/png", PNG))
                        .param("title", "con allegato").header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Matcher image = Pattern.compile("src=\"/images/([^\"]+)\"").matcher(fragment);
        assertThat(image.find()).as(fragment).isTrue();
        mockMvc.perform(get("/images/" + image.group(1))).andExpect(status().isOk());

        Matcher delete = Pattern.compile("hx-post=\"/example/(\\d+)/delete\"").matcher(fragment);
        assertThat(delete.find()).isTrue();
        String after = mockMvc.perform(post("/example/" + delete.group(1) + "/delete").header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(after).doesNotContain("con allegato");
        mockMvc.perform(get("/images/" + image.group(1))).andExpect(status().isNotFound());
    }

    /** Un upload che non e' un'immagine e' un rifiuto atteso: messaggio inline, nessuna voce. */
    @Test
    @Transactional
    void aNonImageAttachmentIsRejectedWithAnInlineMessage() throws Exception {
        String fragment = mockMvc.perform(multipart("/example").file(new MockMultipartFile("attachment", "a.txt", "text/plain", "ciao".getBytes()))
                        .param("title", "senza allegato valido").header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(fragment).doesNotContain("senza allegato valido").contains("text-danger");
    }

    /** Il resolver dell'app traduce il subject {@code example:<id>} in un link nel registro eventi del core. */
    @Test
    void theEventLogLinksAnExampleSubjectToItsPage() {
        try {
            systemEvents.warn(CoreEventSource.INTERNAL, "test", "example:1", "prova");

            var links = systemEvents.linksFor(systemEvents.list(null, 0, 10).content());

            assertThat(links.values()).flatExtracting(l -> l).extracting(EventLink::path).contains("/example");
        } finally {
            systemEvents.clear();
        }
    }

    @Test
    void theTokenPageOffersTheExampleProvider() {
        assertThat(apiTokens.providers()).contains("EXAMPLE");
    }

    @Test
    void theManualPageOfTheExampleIsServed() throws Exception {
        assertThat(mockMvc.perform(get("/manual")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).contains("Esempio");
        mockMvc.perform(get("/manual/esempio")).andExpect(status().isOk());
        // la guida dello sviluppatore (gruppo "sviluppo") e' distribuita dall'archetype
        assertThat(mockMvc.perform(get("/manual/introduzione")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .contains("Introduzione per lo sviluppatore", "sdurz75.github.io");
    }

    /** Una chiave aggiunta a un bundle e dimenticata nell'altro. */
    @Test
    void messageBundlesHaveMatchingKeys() throws IOException {
        assertThat(load("/messages.properties").keySet()).containsExactlyInAnyOrderElementsOf(load("/messages_en.properties").keySet());
    }

    /** Una chiave dell'app ridefinita da una libreria (o viceversa): il bundle che vince dipende dall'ordine, il testo cambia in silenzio. */
    @Test
    void appBundleKeysAreDisjointFromTheLibraryBundles() throws IOException {
        Properties app = load("/messages.properties");
        assertThat(app.keySet()).doesNotContainAnyElementsOf(load("/messages-core.properties").keySet());
#if( $useAi == "true" )
        assertThat(app.keySet()).doesNotContainAnyElementsOf(load("/messages-ai.properties").keySet());
#end
    }

    private static Properties load(String resource) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = PagesRenderingTests.class.getResourceAsStream(resource)) {
            properties.load(in);
        }
        return properties;
    }
}
