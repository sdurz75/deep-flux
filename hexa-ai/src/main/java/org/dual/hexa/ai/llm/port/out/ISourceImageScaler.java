package org.dual.hexa.ai.llm.port.out;

import org.dual.hexa.ai.llm.domain.ImageScalingException;
import org.dual.hexa.core.storage.domain.SourceImage;

/** Riduce le immagini grandi prima di mandarle al modello di visione (ogni pixel in piu' sono token pagati). */
public interface ISourceImageScaler {

    /**
     * L'immagine adatta all'invio: la stessa se e' gia' piccola o se il formato non si sa decodificare (es. webp), altrimenti una
     * versione ridotta.
     *
     * @throws ImageScalingException se un formato decodificabile e' illeggibile: non si invia l'originale
     */
    SourceImage fitForVision(SourceImage image);
}
