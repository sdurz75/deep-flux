package org.hexa.core.ai.domain;

import java.util.List;

/** Il risultato dell'analisi di contenuto di un'immagine: una descrizione in prosa e tag d'indice (it/en). */
public record ImageDescription(String description, List<String> tags) {

    public ImageDescription {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
