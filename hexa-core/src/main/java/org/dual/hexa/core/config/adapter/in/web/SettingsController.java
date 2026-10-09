package org.dual.hexa.core.config.adapter.in.web;

import java.util.List;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.dual.hexa.core.config.domain.ConfigException;
import org.dual.hexa.core.config.domain.FormState;
import org.dual.hexa.core.config.port.in.IConfigModule;
import org.dual.hexa.core.config.port.in.IModuleSettings;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.dual.hexa.core.web.HtmxEvents;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * La pagina «Impostazioni», autogenerata dai moduli registrati ({@code IConfigModule}): una sezione per modulo, ognuna col proprio form. Stessa URL,
 * due risposte distinte da {@code HX-Request} (pagina intera o solo la sezione). Un valore non valido ({@code ConfigException}) e' un rifiuto atteso:
 * messaggio nella sezione, niente registro eventi, e il form mostra quanto inviato.
 */
@Controller
class SettingsController {

    private static final String SECTION = "fragments/core/settings-section :: section(view=${view}, error=${error}, saved=${saved})";

    private final IModuleSettings settings;

    private final HtmxEvents htmx;
    private final Messages messages;

    SettingsController(IModuleSettings settings, HtmxEvents htmx, Messages messages) {
        this.settings = settings;
        this.htmx = htmx;
        this.messages = messages;
    }

    @GetMapping("/settings")
    String page(Model model) {
        model.addAttribute("views", settings.modules().stream().map(module -> view(module, settings.formState(module.id()), null, false)).toList());
        return "core/settings";
    }

    /** Lo stato SALVATO di un modulo (il bottone «Annulla modifiche»): scarta cio' che l'utente ha digitato e l'eventuale errore. */
    @GetMapping("/settings/{module}")
    String section(@PathVariable("module") String moduleId, Model model, @RequestHeader(value = "HX-Request", required = false) String hx) {
        IConfigModule module = find(moduleId);
        if (hx == null) {
            return "redirect:/settings";
        }
        return render(model, hx, view(module, settings.formState(moduleId), null, false));
    }

    @PostMapping("/settings/{module}")
    String save(@PathVariable("module") String moduleId, @RequestParam Map<String, String> form, Model model, HttpServletResponse response,
                @RequestHeader(value = "HX-Request", required = false) String hx) {
        IConfigModule module = find(moduleId);
        try {
            settings.save(moduleId, form);
        } catch (ConfigException e) {
            return render(model, hx, view(module, settings.preview(moduleId, form), e.getMessage(), false));
        }
        successToast(response, messages.get("settings.saved.module", messages.getOrDefault(module.titleKey(), module.id())), module);
        return render(model, hx, view(module, settings.formState(moduleId), null, true));
    }

    @PostMapping("/settings/{module}/reset")
    String reset(@PathVariable("module") String moduleId, Model model, HttpServletResponse response,
                 @RequestHeader(value = "HX-Request", required = false) String hx) {
        IConfigModule module = find(moduleId);
        settings.reset(moduleId);
        successToast(response, messages.get("settings.reset.done"), module);
        return render(model, hx, view(module, settings.formState(moduleId), null, true));
    }

    /** Toast di successo (HX-Trigger {@code system-toast}); la chiave e' unica a ogni esito, perche' il client deduplica per chiave. */
    private void successToast(HttpServletResponse response, String message, IConfigModule module) {
        Map<String, Object> toast = new LinkedHashMap<>();
        toast.put("key", "settings-" + module.id() + "-" + System.nanoTime());
        toast.put("message", message);
        toast.put("transient", false);
        toast.put("severity", "SUCCESS");
        htmx.addHxTrigger(response, "system-toast", toast);
    }

    private String render(Model model, String hx, SettingsView view) {
        model.addAttribute("view", view);
        model.addAttribute("error", view.error());
        model.addAttribute("saved", view.saved());
        if (hx != null) {
            return SECTION;
        }
        model.addAttribute("views", settings.modules().stream()
                .map(module -> module.id().equals(view.id()) ? view : view(module, settings.formState(module.id()), null, false)).toList());
        return "core/settings";
    }

    private IConfigModule find(String moduleId) {
        return settings.module(moduleId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private SettingsView view(IConfigModule module, FormState state, String error, boolean saved) {
        return new SettingsView(module.id(), module.titleKey(), List.copyOf(module.fields()), state, module.fragment().orElse(null), error, saved);
    }
}
