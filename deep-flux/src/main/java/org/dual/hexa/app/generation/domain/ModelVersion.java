package org.dual.hexa.app.generation.domain;

import java.util.Set;

/**
 * Ultima versione pubblicata di un modello sul servizio di generazione: l'hash da pinnare e i nomi dei campi di input del suo schema
 * (vuoto = schema non noto).
 */
public record ModelVersion(String id, Set<String> inputFields) {

    public ModelVersion {
        inputFields = inputFields == null ? Set.of() : Set.copyOf(inputFields);
    }
}
