package org.dual.hexa.app.generation.port.in;

import java.util.List;

import org.dual.hexa.app.generation.domain.ImportReport;
import org.dual.hexa.core.storage.domain.UploadedFile;

/**
 * Immagini di provenienza esterna: si ricevono, si archiviano come {@code Generation} con origine IMPORTED (subito visibili in galleria e usabili come
 * sorgente) e se ne analizza il contenuto con un modello di visione, in background, per indicizzarle semanticamente.
 */
public interface IImportedImages {

    /** Quanti file al massimo accetta UNA importazione (config {@code app.import.max-files}): gli eccedenti sono rifiutati con un motivo. */
    int maxFiles();

    /**
     * Salva ogni file con un nome nuovo e ne crea la riga. Un file non valido (tipo, dimensione) o eccedente e' rifiutato con un motivo
     * nell'esito e NON ferma gli altri; i file vuoti (un campo file non compilato) si saltano senza comparire nell'esito. L'analisi
     * parte in background dopo il commit.
     */
    ImportReport importImages(List<UploadedFile> files);

    /** Analizza il contenuto dell'immagine importata {@code generationId}. Idempotente: non fa nulla se non e' da analizzare. Non lancia mai. */
    void analyze(Long generationId);

    /** Riporta un'analisi non riuscita allo stato da eseguire e la rilancia in background. Un id non importato o inesistente e' un rifiuto. */
    void retryAnalysis(Long generationId);

    /** Riavvia le analisi rimaste in sospeso: tutte all'avvio ({@code startup}), altrimenti solo quelle piu' vecchie della tolleranza. Ritorna quante. */
    int recoverPendingAnalyses(boolean startup);
}
