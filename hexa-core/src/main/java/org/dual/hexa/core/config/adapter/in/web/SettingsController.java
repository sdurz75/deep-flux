package org.dual.hexa.core.config.adapter.in.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.dual.hexa.core.config.domain.ConfigException;
import org.dual.hexa.core.config.domain.ConfigField;
import org.dual.hexa.core.config.domain.ModuleValues;
import org.dual.hexa.core.config.port.in.IConfigModule;
import org.dual.hexa.core.config.port.in.IModuleSettings;
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

    SettingsController(IModuleSettings settings) {
        this.settings = settings;
    }

    @GetMapping("/settings")
    String page(Model model) {
        model.addAttribute("views", settings.modules().stream().map(module -> view(module, effective(module), null, false)).toList());
        return "core/settings";
    }

    @PostMapping("/settings/{module}")
    String save(@PathVariable("module") String moduleId, @RequestParam Map<String, String> form, Model model,
                @RequestHeader(value = "HX-Request", required = false) String htmx) {
        IConfigModule module = find(moduleId);
        try {
            settings.save(moduleId, form);
        } catch (ConfigException e) {
            return render(model, htmx, view(module, submitted(module, form), e.getMessage(), false));
        }
        return render(model, htmx, view(module, effective(module), null, true));
    }

    @PostMapping("/settings/{module}/reset")
    String reset(@PathVariable("module") String moduleId, Model model, @RequestHeader(value = "HX-Request", required = false) String htmx) {
        IConfigModule module = find(moduleId);
        settings.reset(moduleId);
        return render(model, htmx, view(module, effective(module), null, true));
    }

    private String render(Model model, String htmx, SettingsView view) {
        model.addAttribute("view", view);
        model.addAttribute("error", view.error());
        model.addAttribute("saved", view.saved());
        if (htmx != null) {
            return SECTION;
        }
        model.addAttribute("views", settings.modules().stream()
                .map(module -> module.id().equals(view.id()) ? view : view(module, effective(module), null, false)).toList());
        return "core/settings";
    }

    private IConfigModule find(String moduleId) {
        return settings.module(moduleId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private Map<String, String> effective(IConfigModule module) {
        ModuleValues values = settings.values(module.id());
        Map<String, String> map = new LinkedHashMap<>();
        for (ConfigField field : module.fields()) {
            map.put(field.key(), values.getString(field.key()));
        }
        return map;
    }

    private Map<String, String> submitted(IConfigModule module, Map<String, String> form) {
        Map<String, String> map = effective(module);
        for (ConfigField field : module.fields()) {
            if (field.type() == ConfigField.Type.BOOL) {
                map.put(field.key(), Boolean.toString(form.containsKey(field.key())));
            } else if (form.containsKey(field.key())) {
                map.put(field.key(), form.get(field.key()));
            }
        }
        return map;
    }

    private SettingsView view(IConfigModule module, Map<String, String> values, String error, boolean saved) {
        return new SettingsView(module.id(), module.titleKey(), List.copyOf(module.fields()), values, module.fragment().orElse(null), error, saved);
    }
}
