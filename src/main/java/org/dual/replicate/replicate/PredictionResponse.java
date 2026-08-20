package org.dual.replicate.replicate;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Sottoinsieme della risposta di GET/POST /v1/predictions{,/{id}} che ci
 * interessa. "output" e' tipizzato Object perche' lo schema varia da
 * modello a modello: puo' essere una stringa (un URL), una lista di
 * stringhe, o assente finche' la prediction non e' completa.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PredictionResponse(
        String id,
        String status,
        Object output,
        String error,
        Map<String, Object> input
) {

    /** Estrae il primo URL utile da "output", qualunque sia la sua forma. */
    public String firstOutputUrl() {
        if (output instanceof String s) {
            return s;
        }
        if (output instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof String s) {
            return s;
        }
        return null;
    }

    public boolean succeeded() {
        return "succeeded".equals(status);
    }

    public boolean failed() {
        return "failed".equals(status) || "canceled".equals(status);
    }
}
