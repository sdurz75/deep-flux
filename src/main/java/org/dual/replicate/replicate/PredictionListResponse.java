package org.dual.replicate.replicate;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Risposta di GET /v1/predictions: una pagina di prediction (le piu'
 * recenti prima) piu' l'URL della pagina successiva, se presente. Usata
 * solo da ReplicateClient#countInProgressPredictions per contare le
 * prediction ancora attive senza dover modellare l'intera risposta
 * (nessun filtro per stato lato server, va paginata e filtrata qui).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PredictionListResponse(
        List<PredictionResponse> results,
        String next
) {
}
