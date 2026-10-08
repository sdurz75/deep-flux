package org.dual.hexa.core.secrets.adapter.in.web;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;
import org.dual.hexa.core.events.port.in.ISystemEvents;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.dual.hexa.core.kernel.remote.RemoteServiceException;
import org.dual.hexa.core.secrets.domain.SecretException;
import org.dual.hexa.core.secrets.domain.SecretType;
import org.dual.hexa.core.secrets.port.in.ISecrets;
import org.dual.hexa.core.web.HtmxEvents;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * CRUD dei segreti (token API di un servizio, password, segreti dei moduli; {@link ISecrets}): pagina {@code /secrets} con l'elenco e un dialog Pines
 * per creare/modificare (stessa meccanica del dialog note di /search: al salvataggio riuscito {@code HX-Trigger: secret-saved} chiude il dialog; con un
 * errore il form si rimpiazza da se' ({@code HX-Retarget}) e il dialog resta aperto). Il valore in chiaro non esce MAI da qui: ne' nel modello, ne' nelle
 * risposte. I segreti di un tipo {@code managed} (dei moduli) si elencano ma non si creano, non si modificano e non si cancellano da qui.
 */
@Controller
@RequestMapping("/secrets")
public class SecretController {

    private final ISecrets secrets;
    private final ISystemEvents systemEvents;
    private final HtmxEvents htmx;
    private final Messages messages;

    public SecretController(ISecrets secrets, ISystemEvents systemEvents, HtmxEvents htmx, Messages messages) {
        this.secrets = secrets;
        this.systemEvents = systemEvents;
        this.htmx = htmx;
        this.messages = messages;
    }

    @GetMapping
    public String page(Model model) {
        populateList(model);
        model.addAttribute("types", creatableTypes());
        formAttributes(model, null, defaultType(), "", "", null);
        return "core/secrets";
    }

    /** Form vuoto per il dialog (caricato a ogni apertura). */
    @GetMapping("/new")
    public String newForm(Model model) {
        return formView(model, null, defaultType(), "", "", null);
    }

    /** Form precompilato (mai il valore: si lascia vuoto per non cambiarlo). */
    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        ISecrets.SecretView secret = secrets.get(id);
        return formView(model, id, secret.type(), secret.name(), secret.expiresAt() == null ? "" : secret.expiresAt().toString(), null);
    }

    @PostMapping
    public String create(@RequestParam(required = false) String type, @RequestParam(defaultValue = "") String name,
                         @RequestParam(defaultValue = "") String value, @RequestParam(defaultValue = "") String expiresAt,
                         HttpServletResponse response, Model model) {
        String parsed = parseType(type);
        try {
            secrets.create(parsed, name, value, parseDate(expiresAt));
        } catch (RemoteServiceException e) {
            return failed(e, response, model, null, parsed == null ? defaultType() : parsed, name, expiresAt);
        }
        return saved(response, model);
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id, @RequestParam(defaultValue = "") String name,
                         @RequestParam(defaultValue = "") String value, @RequestParam(defaultValue = "") String expiresAt,
                         HttpServletResponse response, Model model) {
        try {
            secrets.update(id, name, value, parseDate(expiresAt));
        } catch (RemoteServiceException e) {
            return failed(e, response, model, id, secrets.get(id).type(), name, expiresAt);
        }
        return saved(response, model);
    }

    @DeleteMapping("/{id}")
    public String delete(@PathVariable Long id, Model model) {
        secrets.delete(id);
        populateList(model);
        return listView();
    }

    // --- interno ------------------------------------------------------------------------------------------------

    private String saved(HttpServletResponse response, Model model) {
        // Chiude il dialog (secrets.html): con un errore di validazione l'evento NON parte e il dialog resta aperto.
        htmx.addHxTrigger(response, "secret-saved", "");
        populateList(model);
        return listView();
    }

    /** Rifiuto atteso: solo messaggio nel form. Guasto vero (es. chiave di cifratura mancante): registrato e notificato anche come toast. */
    private String failed(RemoteServiceException e, HttpServletResponse response, Model model, Long id, String type,
                          String name, String expiresAt) {
        if (e.isReportable()) {
            htmx.addToastHeader(response, systemEvents.record(id == null ? "createSecret" : "updateSecret", e));
        }
        response.setHeader("HX-Retarget", "#secret-form");
        response.setHeader("HX-Reswap", "outerHTML");
        return formView(model, id, type, name, expiresAt, e.getMessage());
    }

    private void populateList(Model model) {
        model.addAttribute("secrets", secrets.list());
        Map<String, String> labels = new LinkedHashMap<>();
        secrets.types().forEach(type -> labels.put(type.name(), messages.getOrDefault(type.labelKey(), type.name())));
        model.addAttribute("typeLabels", labels);
        model.addAttribute("configured", secrets.isConfigured());
        model.addAttribute("warningDays", secrets.warningDays());
    }

    private static void formAttributes(Model model, Long id, String type, String name, String expiresAt, String error) {
        model.addAttribute("secretId", id);
        model.addAttribute("secretType", type);
        model.addAttribute("secretName", name);
        model.addAttribute("secretExpires", expiresAt);
        model.addAttribute("secretError", error);
    }

    private String formView(Model model, Long id, String type, String name, String expiresAt, String error) {
        formAttributes(model, id, type, name, expiresAt, error);
        model.addAttribute("types", creatableTypes());
        model.addAttribute("configured", secrets.isConfigured());
        return "fragments/core/secrets :: secretForm(secretId=${secretId}, secretType=${secretType}, secretName=${secretName}, "
                + "secretExpires=${secretExpires}, secretError=${secretError}, types=${types}, configured=${configured})";
    }

    private static String listView() {
        return "fragments/core/secrets :: list(secrets=${secrets}, typeLabels=${typeLabels}, configured=${configured}, warningDays=${warningDays})";
    }

    /** I tipi che si possono creare a mano: quelli registrati, tolti i {@code managed}. */
    private List<SecretType> creatableTypes() {
        return secrets.types().stream().filter(type -> !type.managed()).toList();
    }

    private String defaultType() {
        return creatableTypes().stream().findFirst().map(SecretType::name).orElse("");
    }

    /** Un tipo sconosciuto equivale a "non scelto" (il servizio lo rifiuta con un messaggio). */
    private String parseType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String upper = value.strip().toUpperCase();
        return creatableTypes().stream().anyMatch(type -> type.name().equals(upper)) ? upper : null;
    }

    /** Vuoto = nessuna scadenza; una data non valida e' un rifiuto con messaggio, non un 400. */
    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw new SecretException(messages.get("secrets.error.expiryInvalid"));
        }
    }
}
