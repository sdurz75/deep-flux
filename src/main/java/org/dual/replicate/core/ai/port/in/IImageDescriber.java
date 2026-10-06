package org.dual.replicate.core.ai.port.in;

import org.dual.replicate.core.ai.domain.ImageDescription;
import org.dual.replicate.core.storage.domain.SourceImage;

/**
 * Analisi di contenuto di un'immagine con un modello di visione: una descrizione in prosa e dei tag, per indicizzarla semanticamente.
 * One-shot, senza conversazione ne' tool (stesso modello di visione e stesso fallback dell'"AI enhance").
 */
public interface IImageDescriber {

    /**
     * @throws org.dual.replicate.core.ai.domain.ImageAnalysisException se il modello rifiuta o risponde in un formato illeggibile
     */
    ImageDescription describe(SourceImage image);
}
