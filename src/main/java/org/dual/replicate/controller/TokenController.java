package org.dual.replicate.controller;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import jakarta.servlet.http.HttpServletResponse;
import org.dual.replicate.domain.ApiTokenProvider;
import org.dual.replicate.i18n.Messages;
import org.dual.replicate.remote.RemoteServiceException;
import org.dual.replicate.service.ApiTokenService;
import org.dual.replicate.service.SystemEventService;
import org.dual.replicate.service.TokenException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * CRUD dei token API (CivitAI/HuggingFace, {@link ApiTokenService}): pagina {@code /tokens} con l'elenco e un dialog Pines
 * per creare/modificare (stessa meccanica del dialog note di {@link SemanticSearchController}: al salvataggio riuscito
 * {@code HX-Trigger: token-saved} chiude il dialog; con un errore il form si rimpiazza da se' ({@code HX-Retarget}) e il
 * dialog resta aperto). Il token in chiaro non esce MAI da qui: ne' nel modello, ne' nelle risposte.
 */
@Controller
@RequestMapping("/tokens")
public class TokenController {

    private final ApiTokenService tokens;
    private final SystemEventService systemEvents;
    private final Messages messages;

    public TokenController(ApiTokenService tokens, SystemEventService systemEvents, Messages messages) {
        this.tokens = tokens;
        this.systemEvents = systemEvents;
        this.messages = messages;
    }

    @GetMapping
    public String page(Model model) {
        populateList(model);
        model.addAttribute("providers", ApiTokenProvider.values());
        formAttributes(model, null, ApiTokenProvider.HUGGINGFACE, "", "", null);
        return "tokens";
    }

    /** Form vuoto per il dialog (caricato a ogni apertura). */
    @GetMapping("/new")
    public String newForm(Model model) {
        return formView(model, null, ApiTokenProvider.HUGGINGFACE, "", "", null);
    }

    /** Form precompilato (mai il token: si lascia vuoto per non cambiarlo). */
    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        ApiTokenService.TokenView token = tokens.get(id);
        return formView(model, id, token.provider(), token.name(), token.expiresAt() == null ? "" : token.expiresAt().toString(), null);
    }

    @PostMapping
    public String create(@RequestParam(required = false) String provider, @RequestParam(defaultValue = "") String name,
                         @RequestParam(defaultValue = "") String token, @RequestParam(defaultValue = "") String expiresAt,
                         HttpServletResponse response, Model model) {
        ApiTokenProvider parsed = parseProvider(provider);
        try {
            tokens.create(parsed, name, token, parseDate(expiresAt));
        } catch (RemoteServiceException e) {
            return failed(e, response, model, null, parsed == null ? ApiTokenProvider.HUGGINGFACE : parsed, name, expiresAt);
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
        systemEvents.addHxTrigger(response, "token-saved", "");
        populateList(model);
        return listView();
    }

    /** Rifiuto atteso: solo messaggio nel form. Guasto vero (es. chiave di cifratura mancante): registrato e notificato anche come toast. */
    private String failed(RemoteServiceException e, HttpServletResponse response, Model model, Long id, ApiTokenProvider provider,
                          String name, String expiresAt) {
        if (e.isReportable()) {
            systemEvents.recordForHtmx(response, id == null ? "createToken" : "updateToken", e);
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

    private static void formAttributes(Model model, Long id, ApiTokenProvider provider, String name, String expiresAt, String error) {
        model.addAttribute("tokenId", id);
        model.addAttribute("tokenProvider", provider.name());
        model.addAttribute("tokenName", name);
        model.addAttribute("tokenExpires", expiresAt);
        model.addAttribute("tokenError", error);
    }

    private String formView(Model model, Long id, ApiTokenProvider provider, String name, String expiresAt, String error) {
        formAttributes(model, id, provider, name, expiresAt, error);
        model.addAttribute("providers", ApiTokenProvider.values());
        model.addAttribute("configured", tokens.isConfigured());
        return "fragments/tokens :: tokenForm(tokenId=${tokenId}, tokenProvider=${tokenProvider}, tokenName=${tokenName}, "
                + "tokenExpires=${tokenExpires}, tokenError=${tokenError}, providers=${providers}, configured=${configured})";
    }

    private static String listView() {
        return "fragments/tokens :: list(tokens=${tokens}, configured=${configured}, warningDays=${warningDays})";
    }

    private static ApiTokenProvider parseProvider(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ApiTokenProvider.valueOf(value.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
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
