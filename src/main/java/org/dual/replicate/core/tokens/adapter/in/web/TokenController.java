package org.dual.replicate.core.tokens.adapter.in.web;

import java.time.LocalDate;
import java.util.List;
import java.time.format.DateTimeParseException;

import jakarta.servlet.http.HttpServletResponse;
import org.dual.replicate.controller.SemanticSearchController;
import org.dual.replicate.core.tokens.port.in.IApiTokens;
import org.dual.replicate.core.tokens.port.out.ITokenProviderCatalog;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.dual.replicate.core.kernel.remote.RemoteServiceException;
import org.dual.replicate.core.web.HtmxEvents;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.dual.replicate.core.tokens.domain.TokenException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * CRUD dei token API (CivitAI/HuggingFace, {@link IApiTokens}): pagina {@code /tokens} con l'elenco e un dialog Pines
 * per creare/modificare (stessa meccanica del dialog note di {@link SemanticSearchController}: al salvataggio riuscito
 * {@code HX-Trigger: token-saved} chiude il dialog; con un errore il form si rimpiazza da se' ({@code HX-Retarget}) e il
 * dialog resta aperto). Il token in chiaro non esce MAI da qui: ne' nel modello, ne' nelle risposte.
 */
@Controller
@RequestMapping("/tokens")
public class TokenController {

    private final IApiTokens tokens;
    private final ObjectProvider<ITokenProviderCatalog> providerCatalog;
    private final ISystemEvents systemEvents;
    private final HtmxEvents htmx;
    private final Messages messages;

    public TokenController(IApiTokens tokens, ISystemEvents systemEvents, HtmxEvents htmx, Messages messages,
                           ObjectProvider<ITokenProviderCatalog> providerCatalog) {
        this.tokens = tokens;
        this.systemEvents = systemEvents;
        this.htmx = htmx;
        this.messages = messages;
        this.providerCatalog = providerCatalog;
    }

    @GetMapping
    public String page(Model model) {
        populateList(model);
        model.addAttribute("providers", providers());
        formAttributes(model, null, defaultProvider(), "", "", null);
        return "tokens";
    }

    /** Form vuoto per il dialog (caricato a ogni apertura). */
    @GetMapping("/new")
    public String newForm(Model model) {
        return formView(model, null, defaultProvider(), "", "", null);
    }

    /** Form precompilato (mai il token: si lascia vuoto per non cambiarlo). */
    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        IApiTokens.TokenView token = tokens.get(id);
        return formView(model, id, token.provider(), token.name(), token.expiresAt() == null ? "" : token.expiresAt().toString(), null);
    }

    @PostMapping
    public String create(@RequestParam(required = false) String provider, @RequestParam(defaultValue = "") String name,
                         @RequestParam(defaultValue = "") String token, @RequestParam(defaultValue = "") String expiresAt,
                         HttpServletResponse response, Model model) {
        String parsed = parseProvider(provider);
        try {
            tokens.create(parsed, name, token, parseDate(expiresAt));
        } catch (RemoteServiceException e) {
            return failed(e, response, model, null, parsed == null ? defaultProvider() : parsed, name, expiresAt);
        }
        return saved(response, model);
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id, @RequestParam(defaultValue = "") String name,
                         @RequestParam(defaultValue = "") String token, @RequestParam(defaultValue = "") String expiresAt,
                         HttpServletResponse response, Model model) {
        try {
            tokens.update(id, name, token, parseDate(expiresAt));
        } catch (RemoteServiceException e) {
            return failed(e, response, model, id, tokens.get(id).provider(), name, expiresAt);
        }
        return saved(response, model);
    }

    @DeleteMapping("/{id}")
    public String delete(@PathVariable Long id, Model model) {
        tokens.delete(id);
        populateList(model);
        return listView();
    }

    // --- interno ------------------------------------------------------------------------------------------------

    private String saved(HttpServletResponse response, Model model) {
        // Chiude il dialog (tokens.html): con un errore di validazione l'evento NON parte e il dialog resta aperto.
        htmx.addHxTrigger(response, "token-saved", "");
        populateList(model);
        return listView();
    }

    /** Rifiuto atteso: solo messaggio nel form. Guasto vero (es. chiave di cifratura mancante): registrato e notificato anche come toast. */
    private String failed(RemoteServiceException e, HttpServletResponse response, Model model, Long id, String provider,
                          String name, String expiresAt) {
        if (e.isReportable()) {
            htmx.addToastHeader(response, systemEvents.record(id == null ? "createToken" : "updateToken", e));
        }
        response.setHeader("HX-Retarget", "#token-form");
        response.setHeader("HX-Reswap", "outerHTML");
        return formView(model, id, provider, name, expiresAt, e.getMessage());
    }

    private void populateList(Model model) {
        model.addAttribute("tokens", tokens.list());
        model.addAttribute("configured", tokens.isConfigured());
        model.addAttribute("warningDays", tokens.warningDays());
    }

    private static void formAttributes(Model model, Long id, String provider, String name, String expiresAt, String error) {
        model.addAttribute("tokenId", id);
        model.addAttribute("tokenProvider", provider);
        model.addAttribute("tokenName", name);
        model.addAttribute("tokenExpires", expiresAt);
        model.addAttribute("tokenError", error);
    }

    private String formView(Model model, Long id, String provider, String name, String expiresAt, String error) {
        formAttributes(model, id, provider, name, expiresAt, error);
        model.addAttribute("providers", providers());
        model.addAttribute("configured", tokens.isConfigured());
        return "fragments/tokens :: tokenForm(tokenId=${tokenId}, tokenProvider=${tokenProvider}, tokenName=${tokenName}, "
                + "tokenExpires=${tokenExpires}, tokenError=${tokenError}, providers=${providers}, configured=${configured})";
    }

    private static String listView() {
        return "fragments/tokens :: list(tokens=${tokens}, configured=${configured}, warningDays=${warningDays})";
    }

    /** I provider offerti dall'app ({@link ITokenProviderCatalog}); nessuna implementazione = nessuno. */
    private List<String> providers() {
        ITokenProviderCatalog catalog = providerCatalog.getIfAvailable();
        return catalog == null ? List.of() : catalog.providers();
    }

    private String defaultProvider() {
        return providers().stream().findFirst().orElse("");
    }

    /** Un provider sconosciuto equivale a "non scelto" (il servizio lo rifiuta con un messaggio). */
    private String parseProvider(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String upper = value.strip().toUpperCase();
        return providers().contains(upper) ? upper : null;
    }

    /** Vuoto = nessuna scadenza; una data non valida e' un rifiuto con messaggio, non un 400. */
    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw new TokenException(messages.get("tokens.error.expiryInvalid"));
        }
    }
}
