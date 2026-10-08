package org.dual.hexa.oauth2.login.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.dual.hexa.core.tokens.port.in.IApiTokens;
import org.dual.hexa.oauth2.login.domain.AllowKind;
import org.dual.hexa.oauth2.login.domain.OAuthException;
import org.dual.hexa.oauth2.login.port.in.IAllowedUsers;
import org.dual.hexa.oauth2.login.port.in.IOAuthAccess;
import org.dual.hexa.oauth2.login.port.in.IOAuthProviders;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * La pagina «Accesso»: provider, utenti ammessi e interruttore del cancello. Stessa URL, due risposte distinte da {@code HX-Request} (pagina intera o solo il
 * pannello). Un rifiuto atteso ({@code OAuthException} REJECTED: formato, duplicato, cancello non attivabile, ultima voce) compare nel pannello, senza riga
 * nel registro eventi.
 */
@Controller
class OAuthAdminController {

    private static final String PANEL = "fragments/core/oauth2-panel :: panel(v=${view})";

    private final IOAuthAccess access;
    private final IOAuthProviders providers;
    private final IAllowedUsers allowed;
    private final IApiTokens tokens;

    OAuthAdminController(IOAuthAccess access, IOAuthProviders providers, IAllowedUsers allowed, IApiTokens tokens) {
        this.access = access;
        this.providers = providers;
        this.allowed = allowed;
        this.tokens = tokens;
    }

    @GetMapping("/oauth2")
    String page(HttpServletRequest request, Model model, @RequestHeader(value = "HX-Request", required = false) String htmx) {
        return render(request, model, htmx, null, false);
    }

    @PostMapping("/oauth2/gate/enable")
    String enable(HttpServletRequest request, Model model, @RequestHeader(value = "HX-Request", required = false) String htmx) {
        return act(request, model, htmx, access::enable);
    }

    @PostMapping("/oauth2/gate/disable")
    String disable(HttpServletRequest request, Model model, @RequestHeader(value = "HX-Request", required = false) String htmx) {
        return act(request, model, htmx, access::disable);
    }

    @PostMapping("/oauth2/providers")
    String addProvider(@RequestParam(defaultValue = "") String slug, @RequestParam(defaultValue = "") String title,
                       @RequestParam(defaultValue = "") String issuerUri, @RequestParam(defaultValue = "") String clientId,
                       @RequestParam(required = false) Long secretTokenId, HttpServletRequest request, Model model,
                       @RequestHeader(value = "HX-Request", required = false) String htmx) {
        return act(request, model, htmx, () -> providers.create(slug, title, issuerUri, clientId, secretTokenId));
    }

    @PostMapping("/oauth2/providers/{id}/delete")
    String deleteProvider(@PathVariable Long id, HttpServletRequest request, Model model, @RequestHeader(value = "HX-Request", required = false) String htmx) {
        return act(request, model, htmx, () -> providers.delete(id));
    }

    @PostMapping("/oauth2/allowed")
    String addAllowed(@RequestParam(defaultValue = "EMAIL") AllowKind kind, @RequestParam(defaultValue = "") String value, HttpServletRequest request,
                      Model model, @RequestHeader(value = "HX-Request", required = false) String htmx) {
        return act(request, model, htmx, () -> allowed.add(kind, value));
    }

    @PostMapping("/oauth2/allowed/{id}/delete")
    String deleteAllowed(@PathVariable Long id, HttpServletRequest request, Model model, @RequestHeader(value = "HX-Request", required = false) String htmx) {
        return act(request, model, htmx, () -> allowed.remove(id));
    }

    private String act(HttpServletRequest request, Model model, String htmx, Runnable action) {
        try {
            action.run();
        } catch (OAuthException e) {
            if (e.isReportable()) {
                throw e;
            }
            return render(request, model, htmx, e.getMessage(), false);
        }
        return render(request, model, htmx, null, true);
    }

    private String render(HttpServletRequest request, Model model, String htmx, String error, boolean saved) {
        String base = ServletUriComponentsBuilder.fromContextPath(request).toUriString();
        List<OAuthAdminView.ProviderRow> rows = providers.list().stream()
                .map(config -> new OAuthAdminView.ProviderRow(config, base + "/login/oauth2/code/" + config.slug())).toList();
        model.addAttribute("view", new OAuthAdminView(access.status(), rows, allowed.list(), tokens.options(IOAuthProviders.TOKEN_PROVIDER),
                tokens.isConfigured(), error, saved));
        return htmx != null ? PANEL : "core/oauth2";
    }
}
