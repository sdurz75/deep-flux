package org.dual.hexa.app.dashboard.adapter.in.web;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import org.dual.hexa.ai.credits.port.in.ICredits;
import org.dual.hexa.app.dashboard.port.in.IDashboard;
import org.dual.hexa.app.generation.domain.DailyActivity;
import org.dual.hexa.app.generation.domain.GenerationStats;
import org.dual.hexa.app.generation.domain.ModelUsage;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * La home: la dashboard di sintesi ({@code templates/app/index.html}). Il render legge solo dati locali; il credito residuo, che puo'
 * interrogare OpenRouter, si carica a parte da {@code GET /dashboard/credits} (htmx) come la barra in basso.
 * <p>
 * Le serie dei grafici (etichette, valori, tooltip) si preparano qui, gia' nella lingua e nel formato della richiesta: i fragment generici
 * ({@code fragments/core/bar-chart}, {@code breakdown}) non formattano nulla.
 */
@Controller
public class DashboardController {

    /** Sotto questa quota di riuscite la tessera prende il tono di avviso. */
    private static final int LOW_SUCCESS_PERCENT = 80;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM");

    private final IDashboard dashboard;
    private final ICredits credits;
    private final Messages messages;

    public DashboardController(IDashboard dashboard, ICredits credits, Messages messages) {
        this.dashboard = dashboard;
        this.credits = credits;
        this.messages = messages;
    }

    @GetMapping("/")
    public String home(Model model, Locale locale) {
        IDashboard.View view = dashboard.view();
        GenerationStats stats = view.generation();
        DateTimeFormatter day = DAY.withLocale(locale);

        List<DailyActivity> daily = stats.daily();
        model.addAttribute("view", view);
        model.addAttribute("windowDays", stats.windowDays());
        model.addAttribute("dailyLabels", daily.stream().map(d -> day.format(d.day())).toList());
        model.addAttribute("dailyValues", daily.stream().map(DailyActivity::total).toList());
        model.addAttribute("dailyTitles", daily.stream().map(d -> messages.get("index.dashboard.activity.day",
                day.format(d.day()), d.succeeded(), d.failed(), money(d.costUsd(), locale))).toList());

        List<ModelUsage> models = stats.topModels();
        model.addAttribute("modelLabels", models.stream().map(ModelUsage::model).toList());
        model.addAttribute("modelValues", models.stream().map(ModelUsage::count).toList());
        model.addAttribute("modelTexts", models.stream().map(m -> messages.get("index.dashboard.models.value", m.count(),
                money(m.costUsd(), locale))).toList());

        model.addAttribute("archiveLabels", List.of(messages.get("index.dashboard.archive.images"),
                messages.get("index.dashboard.archive.videos"), messages.get("index.dashboard.archive.imported")));
        model.addAttribute("archiveValues", List.of(stats.images(), stats.videos(), stats.imported()));
        model.addAttribute("archiveTexts", List.of(String.valueOf(stats.images()), String.valueOf(stats.videos()),
                String.valueOf(stats.imported())));

        model.addAttribute("costWindow", money(stats.costWindow(), locale));
        model.addAttribute("costLast7", money(stats.costLast7(), locale));
        model.addAttribute("costTotal", money(stats.costTotal(), locale));
        model.addAttribute("successTone", stats.successPercent() >= 0 && stats.successPercent() < LOW_SUCCESS_PERCENT ? "warning" : "default");
        model.addAttribute("successRate", stats.successPercent() < 0 ? "-" : stats.successPercent() + "%");
        return "app/index";
    }

    /** Le tessere del credito residuo (lazy: puo' chiamare OpenRouter). */
    @GetMapping("/dashboard/credits")
    public String credits(Model model) {
        model.addAttribute("creditLines", credits.lines());
        return "fragments/app/dashboard :: credits(creditLines=${creditLines})";
    }

    private static String money(BigDecimal amount, Locale locale) {
        return String.format(locale, "$%,.2f", amount == null ? BigDecimal.ZERO : amount);
    }
}
