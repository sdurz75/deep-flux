package org.hexa.app.manual;

import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hexa.core.events.port.out.ISystemEventStore;
import org.hexa.core.manual.domain.ManualEntry;
import org.hexa.core.manual.port.in.IManual;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Il manuale di questa app nel contesto vero (pagine e gruppi sono dell'app): pagine, indice, prefisso del reverse proxy, 404, e i link del manuale verso l'app contro le rotte reali. */
@SpringBootTest
@AutoConfigureMockMvc
class ManualControllerTest {

    private static final Pattern HREF = Pattern.compile("href=\"(/[^\"]*)\"");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IManual manual;

    @Autowired
    private ISystemEventStore systemEvents;

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private Environment environment;

    private String body(String path) throws Exception {
        return mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void theIndexListsTheGroupsAndEveryPage() throws Exception {
        String page = body("/manual");

        assertThat(page).contains("Uso").contains("Architettura");
        for (ManualEntry entry : manual.contents("it")) {
            assertThat(page).as(entry.slug()).contains("href=\"/manual/" + entry.slug() + "\"").contains(entry.title());
        }
    }

    @Test
    void aPageShowsItsTitleItsSectionsAndTheNavigation() throws Exception {
        String page = body("/manual/genera-immagini");

        assertThat(page).contains("<h1 id=\"genera-immagini\">Genera immagini</h1>")
                .contains("<h2 id=\"parametri\">")
                .contains("aria-label=\"In questa pagina\"")
                .contains("href=\"#parametri\"")
                // La pagina corrente e' evidenziata nell'elenco e c'e' il passo successivo.
                .contains("aria-current=\"page\"")
                .contains("Modifica e inpainting");
        assertThat(page).containsPattern("<title>[^<]*Genera immagini[^<]*</title>");
        // L'indice "In questa pagina" sta DOPO il titolo (su mobile stava sopra) e prima del corpo.
        int title = page.indexOf("<h1 id=\"genera-immagini\">");
        int toc = page.indexOf("aria-label=\"In questa pagina\"");
        assertThat(title).isPositive().isLessThan(toc);
        assertThat(toc).isLessThan(page.indexOf("<h2 id=\"il-modello\">"));
    }

    @Test
    void anUnknownPageIsA404WithoutAnEventInTheLog() throws Exception {
        long before = systemEvents.count();

        mockMvc.perform(get("/manual/non-esiste")).andExpect(status().isNotFound());
        mockMvc.perform(get("/manual/..%2F..%2Fetc%2Fpasswd")).andExpect(status().is4xxClientError());

        assertThat(systemEvents.count()).isEqualTo(before);
    }

    @Test
    void linksCarryTheForwardedPrefixOfAReverseProxy() throws Exception {
        String page = mockMvc.perform(get("/manual/introduzione").header("X-Forwarded-Prefix", "/proxy"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(page).contains("href=\"/proxy/manual/deep-chat\"")   // dentro il testo del manuale
                .contains("href=\"/proxy/gallery\"")                    // verso l'app, dal testo
                .contains("href=\"/proxy/manual/introduzione\"")        // l'elenco delle pagine (Thymeleaf)
                .doesNotContain("href=\"/manual/").doesNotContain("href=\"/gallery\"");
    }

    /** Ogni percorso dell'app citato dal manuale deve corrispondere a una rotta vera: se una pagina cambia indirizzo, il manuale se ne accorge. */
    @Test
    void everyAppPathTheManualLinksToIsARealRoute() throws Exception {
        Set<String> paths = new TreeSet<>();
        for (ManualEntry entry : manual.contents("it")) {
            Matcher matcher = HREF.matcher(manual.page(entry.slug(), "it", "").orElseThrow().html());
            while (matcher.find()) {
                String path = matcher.group(1).replaceFirst("[?#].*$", "");
                if (!path.startsWith("/manual")) {
                    paths.add(path);
                }
            }
        }
        assertThat(paths).as("percorsi dell'app citati").isNotEmpty();
        // /search esiste solo con la ricerca semantica attiva, e nei test e' spenta (application-test.yml): l'unica rotta facoltativa.
        boolean searchEnabled = "true".equals(environment.getProperty("app.search.enabled", "true"));
        for (String path : paths) {
            if (path.equals("/search") && !searchEnabled) {
                continue;
            }
            assertThat(handlerMapping.getHandler(new MockHttpServletRequest("GET", path))).as("rotta per " + path).isNotNull();
        }
    }
}
