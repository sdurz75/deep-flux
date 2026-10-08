package org.dual.hexa.app.generation.adapter.in.web;

import jakarta.servlet.http.HttpServletResponse;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.dual.hexa.core.kernel.remote.RemoteServiceException;
import org.dual.hexa.core.web.HtmxEvents;
import org.dual.hexa.app.generation.domain.AppSecretType;
import org.dual.hexa.app.generation.domain.LoraException;
import org.dual.hexa.app.generation.port.in.ILoraPresets;
import org.dual.hexa.core.events.port.in.ISystemEvents;
import org.dual.hexa.core.secrets.port.in.ISecrets;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * CRUD dei LoRA anagrafati ({@link ILoraPresets}): pagina {@code /loras} con l'elenco e un dialog Pines per
 * creare/modificare, stessa meccanica di {@code SecretController} (al salvataggio riuscito {@code HX-Trigger: lora-saved}
 * chiude il dialog; con un errore il form si rimpiazza da se' via {@code HX-Retarget} e il dialog resta aperto).
 */
@Controller
@RequestMapping("/loras")
public class LoraController {

    private final ILoraPresets loras;
    private final ISystemEvents systemEvents;
    private final HtmxEvents htmx;
    private final Messages messages;
    private final ISecrets secrets;

    public LoraController(ILoraPresets loras, ISystemEvents systemEvents, HtmxEvents htmx, Messages messages, ISecrets secrets) {
        this.secrets = secrets;
        this.loras = loras;
        this.systemEvents = systemEvents;
        this.htmx = htmx;
        this.messages = messages;
    }

    @GetMapping
    public String page(Model model) {
        populateList(model);
        formAttributes(model, null, "", "", String.valueOf(ILoraPresets.DEFAULT_SCALE), "", "", null, null);
        return "app/loras";
    }

    /** Form vuoto per il dialog (caricato a ogni apertura). */
    @GetMapping("/new")
    public String newForm(Model model) {
        return formView(model, null, "", "", String.valueOf(ILoraPresets.DEFAULT_SCALE), "", "", null, null);
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        ILoraPresets.LoraView lora = loras.get(id);
        return formView(model, id, lora.name(), lora.source(), String.valueOf(lora.scale()),
                lora.triggerWords() == null ? "" : lora.triggerWords(), lora.note() == null ? "" : lora.note(), null, lora.defaultSecretId());
    }

    @PostMapping
    public String create(@RequestParam(defaultValue = "") String name, @RequestParam(defaultValue = "") String source,
                         @RequestParam(defaultValue = "") String scale, @RequestParam(defaultValue = "") String triggerWords,
                         @RequestParam(defaultValue = "") String note,
                         @RequestParam(defaultValue = "") String secretId, HttpServletResponse response, Model model) {
        try {
            loras.create(name, source, parseScale(scale), triggerWords, note, parseSecretId(secretId));
        } catch (RemoteServiceException e) {
            return failed(e, response, model, null, name, source, scale, triggerWords, note, secretId);
        }
        return saved(response, model);
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id, @RequestParam(defaultValue = "") String name, @RequestParam(defaultValue = "") String source,
                         @RequestParam(defaultValue = "") String scale, @RequestParam(defaultValue = "") String triggerWords,
                         @RequestParam(defaultValue = "") String note,
                         @RequestParam(defaultValue = "") String secretId, HttpServletResponse response, Model model) {
        try {
            loras.update(id, name, source, parseScale(scale), triggerWords, note, parseSecretId(secretId));
        } catch (RemoteServiceException e) {
            return failed(e, response, model, id, name, source, scale, triggerWords, note, secretId);
        }
        return saved(response, model);
    }

    @DeleteMapping("/{id}")
    public String delete(@PathVariable Long id, Model model) {
        loras.delete(id);
        populateList(model);
        return listView();
    }

    // --- interno ------------------------------------------------------------------------------------------------

    private String saved(HttpServletResponse response, Model model) {
        // Chiude il dialog (loras.html): con un errore di validazione l'evento NON parte e il dialog resta aperto.
        htmx.addHxTrigger(response, "lora-saved", "");
        populateList(model);
        return listView();
    }

    private String failed(RemoteServiceException e, HttpServletResponse response, Model model, Long id, String name, String source,
                          String scale, String triggerWords, String note, String secretId) {
        if (e.isReportable()) {
            htmx.addToastHeader(response, systemEvents.record(id == null ? "createLora" : "updateLora", e));
        }
        response.setHeader("HX-Retarget", "#lora-form");
        response.setHeader("HX-Reswap", "outerHTML");
        return formView(model, id, name, source, scale, triggerWords, note, e.getMessage(), secretIdOrNull(secretId));
    }

    private void populateList(Model model) {
        model.addAttribute("loras", loras.list());
    }

    private void formAttributes(Model model, Long id, String name, String source, String scale, String triggerWords,
                                String note, String error, Long secretId) {
        model.addAttribute("loraSecretId", secretId);
        model.addAttribute("loraSecrets", java.util.Arrays.stream(AppSecretType.values())
                .flatMap(provider -> secrets.options(provider.name()).stream()).toList());
        model.addAttribute("loraId", id);
        model.addAttribute("loraName", name);
        model.addAttribute("loraSource", source);
        model.addAttribute("loraScale", scale);
        model.addAttribute("loraTriggerWords", triggerWords);
        model.addAttribute("loraNote", note);
        model.addAttribute("loraError", error);
    }

    private String formView(Model model, Long id, String name, String source, String scale, String triggerWords, String note,
                            String error, Long secretId) {
        formAttributes(model, id, name, source, scale, triggerWords, note, error, secretId);
        return "fragments/app/loras :: loraForm(loraId=${loraId}, loraName=${loraName}, loraSource=${loraSource}, loraScale=${loraScale}, "
                + "loraTriggerWords=${loraTriggerWords}, loraNote=${loraNote}, loraError=${loraError}, loraSecretId=${loraSecretId}, loraSecrets=${loraSecrets})";
    }

    private static String listView() {
        return "fragments/app/loras :: list(loras=${loras})";
    }

    /** Vuoto = nessun token; un valore non numerico e' un rifiuto con messaggio, non un 400. */
    private Long parseSecretId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Long id = secretIdOrNull(value);
        if (id == null) {
            throw new LoraException(messages.get("loras.error.tokenInvalid"));
        }
        return id;
    }

    private static Long secretIdOrNull(String value) {
        try {
            return value == null || value.isBlank() ? null : Long.valueOf(value.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Vuoto = intensita' predefinita; un numero non valido e' un rifiuto con messaggio, non un 400. */
    private Double parseScale(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(value.strip().replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new LoraException(messages.get("loras.error.scaleInvalid"));
        }
    }
}
