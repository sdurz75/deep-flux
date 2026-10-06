package org.dual.replicate.core.web;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;
import org.dual.replicate.core.kernel.ToastMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Il lato HTTP/htmx dei toast: aggiunge alla risposta l'header {@code HX-Trigger} che fa comparire il toast (evento
 * {@code system-toast}, vedi fragments/core/toast.html) o altri eventi client, SENZA sovrascrivere quelli gia' presenti. Sta nel kit
 * web e non nella porta del registro eventi (non conosce il protocollo HTTP); non dipende dagli eventi: riceve un {@link ToastMessage}.
 */
@Component
public class HtmxEvents {

    private static final Logger log = LoggerFactory.getLogger(HtmxEvents.class);

    private final ObjectMapper objectMapper;

    public HtmxEvents(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Aggiunge alla risposta htmx l'header {@code HX-Trigger} che fa comparire il toast (evento {@code system-toast}). Sempre
     * presente, anche per una ripetizione di serie: e' l'utente che ha appena provato un'azione e deve saperne l'esito.
     */
    public void addToastHeader(HttpServletResponse response, ToastMessage toastMessage) {
        Map<String, Object> toast = new LinkedHashMap<>();
        toast.put("key", toastMessage.key());
        toast.put("message", toastMessage.message());
        toast.put("transient", toastMessage.transientFailure());
        toast.put("severity", toastMessage.severityName());
        addHxTrigger(response, "system-toast", toast);
    }

    /**
     * Aggiunge un evento all'header {@code HX-Trigger} SENZA sovrascrivere quelli gia' presenti (un controller puo' emettere
     * {@code gallery-update} e, nello stesso giro, un toast). Il valore esistente puo' essere un elenco di nomi ("a, b") o un
     * oggetto JSON; il risultato e' sempre un unico oggetto JSON {@code {evento: dettaglio}}.
     */
    public void addHxTrigger(HttpServletResponse response, String event, Object detail) {
        try {
            Map<String, Object> triggers = new LinkedHashMap<>();
            String existing = response.getHeader("HX-Trigger");
            if (existing != null && !existing.isBlank()) {
                if (existing.trim().startsWith("{")) {
                    triggers.putAll(objectMapper.readValue(existing, new TypeReference<Map<String, Object>>() {
                    }));
                } else {
                    for (String name : existing.split(",")) {
                        if (!name.isBlank()) {
                            triggers.put(name.trim(), "");
                        }
                    }
                }
            }
            triggers.put(event, detail);
            response.setHeader("HX-Trigger", objectMapper.writeValueAsString(triggers));
        } catch (RuntimeException e) {
            log.error("Impossibile costruire l'header HX-Trigger ({}): {}", event, e.toString());
        }
    }
}
