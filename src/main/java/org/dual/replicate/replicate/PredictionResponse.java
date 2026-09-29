package org.dual.replicate.replicate;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Sottoinsieme della risposta di GET/POST /v1/predictions{,/{id}} che ci
 * interessa. "output" e' tipizzato Object perche' lo schema varia da
 * modello a modello: puo' essere una stringa (un URL), una lista di
 * stringhe, o assente finche' la prediction non e' completa. "metrics"
 * (predict_time piu' campi specifici del modello, es. numero di immagini
 * o durata del video) serve solo a stimare il costo, vedi ReplicatePricing.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PredictionResponse(
        String id,
        String status,
        Object output,
        String error,
        Map<String, Object> input,
        String logs,
        Map<String, Object> metrics
) {

    /** Senza metrics (null): le predizioni non ancora completate non le hanno. */
    public PredictionResponse(String id, String status, Object output, String error,
                              Map<String, Object> input, String logs) {
        this(id, status, output, error, input, logs, null);
    }

    /**
     * Estrae tutti gli URL utili da "output", qualunque sia la sua forma
     * (una stringa singola, o una lista quando num_outputs > 1 chiede
     * piu' di un'immagine per richiesta). Lista vuota se assente o di
     * forma inattesa, mai null: il chiamante (GenerationService.refresh)
     * tratta una lista vuota come "nessun output", non ha bisogno di un
     * caso null separato.
     */
    public List<String> outputUrls() {
        if (output instanceof String s) {
            return List.of(s);
        }
        if (output instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        return List.of();
    }

    public boolean succeeded() {
        return "succeeded".equals(status);
    }

    public boolean canceled() {
        return "canceled".equals(status);
    }

    public boolean failed() {
        return "failed".equals(status) || "canceled".equals(status);
    }
}
