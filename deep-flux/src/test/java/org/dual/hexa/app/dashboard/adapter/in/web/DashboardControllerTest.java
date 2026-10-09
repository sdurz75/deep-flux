package org.dual.hexa.app.dashboard.adapter.in.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.dual.hexa.ai.chat.domain.ChatStats;
import org.dual.hexa.ai.credits.domain.CreditLine;
import org.dual.hexa.ai.credits.port.in.ICredits;
import org.dual.hexa.app.dashboard.port.in.IDashboard;
import org.dual.hexa.app.generation.domain.DailyActivity;
import org.dual.hexa.app.generation.domain.GalleryItem;
import org.dual.hexa.app.generation.domain.Generation;
import org.dual.hexa.app.generation.domain.GenerationStats;
import org.dual.hexa.app.generation.domain.ModelUsage;
import org.dual.hexa.core.events.port.in.ISystemEvents;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** La home vista dal browser, con i dati della dashboard finti: i grafici si pilotano senza toccare il DB. */
@SpringBootTest
@AutoConfigureMockMvc
class DashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IDashboard dashboard;

    @MockitoBean
    private ICredits credits;

    private static IDashboard.View view(GenerationStats stats, List<GalleryItem> recent, ISystemEvents.Unseen unseen) {
        return new IDashboard.View(stats, new ChatStats(3, 12, null), Map.of(), 2, recent, unseen);
    }

    private static List<DailyActivity> days(long... totals) {
        List<DailyActivity> daily = new ArrayList<>();
        LocalDate day = LocalDate.of(2026, 10, 8).minusDays(totals.length - 1L);
        for (long total : totals) {
            daily.add(new DailyActivity(day, total, 0, BigDecimal.ZERO));
            day = day.plusDays(1);
        }
        return daily;
    }

    private String home() throws Exception {
        String page = mockMvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return page.substring(page.indexOf("<main"), page.indexOf("</main>"));
    }

    @Test
    void emptyArchiveShowsFriendlyEmptyStatesInsteadOfCharts() throws Exception {
        when(dashboard.view()).thenReturn(view(new GenerationStats(0, 0, 0, 0, 0, 0, 30, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, days(0, 0, 0), List.of()), List.of(), new ISystemEvents.Unseen(0, List.of(), false)));

        String content = home();

        assertThat(content).contains("Nessuna generazione nel periodo").contains("Nessun modello usato")
                .contains("Ancora nessuna immagine").contains("Tutto tranquillo")
                .doesNotContain("role=\"img\"");
        // il credito e' lazy: nel render c'e' solo il contenitore htmx
        assertThat(content).contains("hx-get=\"/dashboard/credits\"");
    }

    @Test
    void populatedDashboardRendersTilesChartBreakdownAndRecentImages() throws Exception {
        Generation generation = mock(Generation.class);
        when(generation.getId()).thenReturn(42L);
        when(generation.getModel()).thenReturn("black-forest-labs/flux-dev");
        GenerationStats stats = new GenerationStats(8, 1, 2, 2, 1, 4, 30, new BigDecimal("1.50"), new BigDecimal("0.50"),
                new BigDecimal("9.99"), days(0, 3, 5), List.of(new ModelUsage("black-forest-labs/flux-dev", 7, new BigDecimal("0.40"))));
        ISystemEvents.Unseen unseen = new ISystemEvents.Unseen(1, List.of(), true);
        when(dashboard.view()).thenReturn(view(stats, List.of(new GalleryItem(generation, "ab.png")), unseen));

        String content = home();

        assertThat(content).contains("role=\"img\"")                        // grafico a colonne
                .contains("black-forest-labs/flux-dev")                      // breakdown dei modelli
                .contains("href=\"/generations/42\"").contains("/images/ab.png") // ultime generazioni
                .contains("Eventi di sistema da leggere: 1")
                .contains("height:100.0%")                                   // la colonna massima riempie il grafico
                .contains("height:0.0%")                                     // il giorno vuoto resta una linea sottile
                .doesNotContain("Nessuna generazione nel periodo");
    }

    @Test
    void creditsFragmentHasOneTilePerProviderAndWarnsWhenNotSet() throws Exception {
        when(credits.lines()).thenReturn(List.of(CreditLine.notSet("REPLICATE", true),
                CreditLine.ok("OPENROUTER", new BigDecimal("12.5"), java.time.Instant.EPOCH, false)));

        String fragment = mockMvc.perform(get("/dashboard/credits")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(fragment).contains("Replicate").contains("Imposta saldo Replicate").contains("OpenRouter").containsPattern("\\$12[.,]50")
                .contains("text-warning");
    }
}
