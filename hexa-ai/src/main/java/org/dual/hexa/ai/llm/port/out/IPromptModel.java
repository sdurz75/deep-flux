package org.dual.hexa.ai.llm.port.out;

import org.dual.hexa.core.storage.domain.SourceImage;

/** Il modello linguistico che riscrive i prompt (oggi OpenRouter via Spring AI). Un solo giro, mai tool. */
public interface IPromptModel {

    /**
     * Completa {@code user} con la guida {@code system}. {@code model} {@code null} = modello di testo di default; {@code image}
     * {@code null} = solo testo (con un'immagine serve un modello di visione). {@code operation} nomina la chiamata per errori e log.
     *
     * @return il testo grezzo del modello (puo' essere nullo o un rifiuto: lo valuta lo use case)
     */
    String complete(String operation, String system, String user, String model, SourceImage image);
}
