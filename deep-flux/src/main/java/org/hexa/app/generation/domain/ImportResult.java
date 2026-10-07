package org.hexa.app.generation.domain;

/**
 * L'esito di UN file di un'importazione: accettato ({@code generationId} e {@code filename} del file salvato, per la miniatura) o rifiutato ({@code rejection}, gia' tradotta, con il
 * motivo). {@code originalName} e' il nome che il client ha dato al file, solo per mostrarlo (mai usato per salvare).
 */
public record ImportResult(String originalName, Long generationId, String filename, String rejection) {

    public static ImportResult accepted(String originalName, Long generationId, String filename) {
        return new ImportResult(originalName, generationId, filename, null);
    }

    public static ImportResult rejected(String originalName, String rejection) {
        return new ImportResult(originalName, null, null, rejection);
    }

    public boolean isAccepted() {
        return generationId != null;
    }
}
