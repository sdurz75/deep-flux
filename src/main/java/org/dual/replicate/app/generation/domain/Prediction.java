package org.dual.replicate.app.generation.domain;

import java.util.List;
import java.util.Map;

/**
 * Lo stato di una prediction remota come lo vede lo use case (sottoinsieme di quanto restituisce il provider). "output" e'
 * tipizzato Object perche' lo schema varia da modello a modello: puo' essere una stringa (un URL), una lista di stringhe, o
 * assente finche' la prediction non e' completa. "metrics" (predict_time piu' campi specifici del modello, es. numero di
 * immagini o durata del video) serve solo a stimare il costo, vedi {@link ReplicatePricing}.
 */
public record Prediction(
        String id,
        String status,
        Object output,
        String error,
        Map<String, Object> input,
        String logs,
        Map<String, Object> metrics
) {

    /** Senza metrics (null): le predizioni non ancora completate non le hanno. */
    public Prediction(String id, String status, Object output, String error, Map<String, Object> input, String logs) {
        this(id, status, output, error, input, logs, null);
    }

    /**
     * Estrae tutti gli URL utili da "output", qualunque sia la sua forma (una stringa singola, o una lista quando
     * num_outputs > 1 chiede piu' di un'immagine per richiesta). Lista vuota se assente o di forma inattesa, mai null.
     */
    public List<String> outputUrls() {
        if (output instanceof String s) {
            return List.of(s);
        }
        if (output instanceof List<?> list) {
            return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
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
