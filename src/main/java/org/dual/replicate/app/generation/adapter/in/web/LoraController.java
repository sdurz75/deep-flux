package org.dual.replicate.app.generation.adapter.in.web;

import jakarta.servlet.http.HttpServletResponse;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.dual.replicate.core.kernel.remote.RemoteServiceException;
import org.dual.replicate.core.tokens.adapter.in.web.TokenController;
import org.dual.replicate.core.web.HtmxEvents;
import org.dual.replicate.app.generation.domain.LoraException;
import org.dual.replicate.app.generation.port.in.ILoraPresets;
import org.dual.replicate.core.events.port.in.ISystemEvents;
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
 * creare/modificare, stessa meccanica di {@link TokenController} (al salvataggio riuscito {@code HX-Trigger: lora-saved}
 * chiude il dialog; con un errore il form si rimpiazza da se' via {@code HX-Retarget} e il dialog resta aperto).
 */
@Controller
@RequestMapping("/loras")
public class LoraController {

    private final ILoraPresets loras;
    private final ISystemEvents systemEvents;
    private final HtmxEvents htmx;
    private final Messages messages;

    public LoraController(ILoraPresets loras, ISystemEvents systemEvents, HtmxEvents htmx, Messages messages) {
        this.loras = loras;
        this.systemEvents = systemEvents;
        this.htmx = htmx;
        this.messages = messages;
    }

    @GetMapping
    public String page(Model model) {
        populateList(model);
        formAttributes(model, null, "", "", String.valueOf(ILoraPresets.DEFAULT_SCALE), "", "", null);
        return "app/loras";
    }

    /** Form vuoto per il dialog (caricato a ogni apertura). */
    @GetMapping("/new")
    public String newForm(Model model) {
        return formView(model, null, "", "", String.valueOf(ILoraPresets.DEFAULT_SCALE), "", "", null);
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        ILoraPresets.LoraView lora = loras.get(id);
        return formView(model, id, lora.name(), lora.source(), String.valueOf(lora.scale()),
                lora.triggerWords() == null ? "" : lora.triggerWords(), lora.note() == null ? "" : lora.note(), null);
    }

    @PostMapping
    public String create(@RequestParam(defaultValue = "") String name, @RequestParam(defaultValue = "") String source,
                         @RequestParam(defaultValue = "") String scale, @RequestParam(defaultValue = "") String triggerWords,
                         @RequestParam(defaultValue = "") String note, HttpServletResponse response, Model model) {
        try {
            loras.create(name, source, parseScale(scale), triggerWords, note);
        } catch (RemoteServiceException e) {
            return failed(e, response, model, null, name, source, scale, triggerWords, note);
        }
        return saved(response, model);
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id, @RequestParam(defaultValue = "") String name, @RequestParam(defaultValue = "") String source,
                         @RequestParam(defaultValue = "") String scale, @RequestParam(defaultValue = "") String triggerWords,
                         @RequestParam(defaultValue = "") String note, HttpServletResponse response, Model model) {
        try {
            loras.update(id, name, source, parseScale(scale), triggerWords, note);
        } catch (RemoteServiceException e) {
            return failed(e, response, model, id, name, source, scale, triggerWords, note);
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
                          String scale, String triggerWords, String note) {
        if (e.isReportable()) {
            htmx.addToastHeader(response, systemEvents.record(id == null ? "createLora" : "updateLora", e));
        }
        response.setHeader("HX-Retarget", "#lora-form");
        response.setHeader("HX-Reswap", "outerHTML");
        return formView(model, id, name, source, scale, triggerWords, note, e.getMessage());
    }

    private void populateList(Model model) {
        model.addAttribute("loras", loras.list());
    }

    private static void formAttributes(Model model, Long id, String name, String source, String scale, String triggerWords,
                                       String note, String error) {
        model.addAttribute("loraId", id);
        model.addAttribute("loraName", name);
        model.addAttribute("loraSource", source);
        model.addAttribute("loraScale", scale);
        model.addAttribute("loraTriggerWords", triggerWords);
        model.addAttribute("loraNote", note);
        model.addAttribute("loraError", error);
    }

    private String formView(Model model, Long id, String name, String source, String scale, String triggerWords, String note,
                            String error) {
        formAttributes(model, id, name, source, scale, triggerWords, note, error);
        return "fragments/app/loras :: loraForm(loraId=${loraId}, loraName=${loraName}, loraSource=${loraSource}, loraScale=${loraScale}, "
                + "loraTriggerWords=${loraTriggerWords}, loraNote=${loraNote}, loraError=${loraError})";
    }

    private static String listView() {
        return "fragments/app/loras :: list(loras=${loras})";
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
