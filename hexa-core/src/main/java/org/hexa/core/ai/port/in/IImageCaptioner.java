package org.hexa.core.ai.port.in;

import org.hexa.core.ai.domain.CaptionStyle;
import org.hexa.core.storage.domain.SourceImage;

/**
 * La didascalia di un'immagine di addestramento con un modello di visione: una riga di prosa inglese che nomina la trigger word. One-shot, senza
 * conversazione ne' tool (stesso modello di visione, stesso fallback e stessi rifiuti dell'"AI enhance" e dell'analisi delle immagini importate).
 */
public interface IImageCaptioner {

    /**
     * @param triggerWord la parola che il LoRA associera' al soggetto o allo stile: la didascalia la contiene sempre (se il modello la dimentica la si antepone)
     * @return una sola riga di testo, mai vuota
     * @throws org.hexa.core.ai.domain.ImageCaptionException se il modello rifiuta o risponde qualcosa di inutilizzabile
     */
    String caption(SourceImage image, String triggerWord, CaptionStyle style);
}
