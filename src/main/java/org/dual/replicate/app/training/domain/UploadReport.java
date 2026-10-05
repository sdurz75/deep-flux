package org.dual.replicate.app.training.domain;

import java.util.List;

/**
 * Esito del caricamento di immagini in un dataset: una riga PER FILE, nell'ordine di arrivo (un rifiuto non ferma gli altri). {@code rejection} e' il
 * motivo gia' tradotto, {@code imageId} l'immagine creata (null se rifiutata).
 */
public record UploadReport(List<Result> results) {

    public record Result(String originalName, boolean accepted, Long imageId, String rejection) {

        public static Result accepted(String originalName, Long imageId) {
            return new Result(originalName, true, imageId, null);
        }

        public static Result rejected(String originalName, String rejection) {
            return new Result(originalName, false, null, rejection);
        }
    }

    public long acceptedCount() {
        return results.stream().filter(Result::accepted).count();
    }

    public long rejectedCount() {
        return results.size() - acceptedCount();
    }
}
