package org.dual.replicate.app.generation.adapter.in.web.form;

import java.util.Map;

import org.dual.replicate.app.generation.domain.GenerationFormType;

/**
 * Costruisce l'input Replicate (vocabolario snake_case) per un
 * {@link GenerationFormType} a partire dai campi sottomessi (form
 * diretta di /generations o pannello impostazioni di /deep-chat, sempre
 * come stringhe: cosi' una singola implementazione serve sia un vero
 * submit HTML sia il payload JSON della chat, che le converte a stringa
 * prima di passarle qui) e sa quale fragment Thymeleaf renderizza quei
 * campi. Un'implementazione esplicita per form-type (vedi
 * {@link GenerationFormRegistry} per il dispatch): non e' un motore
 * di schema dinamico, aggiungere una form significa aggiungere una nuova
 * implementazione, non generalizzare questa interfaccia.
 */
public interface IGenerationParameterHandler {

    GenerationFormType formType();

    /** Solo i campi presenti in {@code submittedFields} finiscono nella mappa: un modello che non supporta un parametro non lo riceve. */
    Map<String, Object> toParameterMap(Map<String, String> submittedFields);

    /** Valori di default per il primo caricamento del form (nessun campo ancora sottomesso). */
    Map<String, Object> defaultFields();

    /** Selettore del fragment Thymeleaf (fragments/generation-params-*.html) che renderizza i campi di questo form-type. */
    String fragmentName();

    /**
     * Helper di parsing/assemblaggio condivisi da ogni implementazione di
     * {@link #toParameterMap}: prima duplicati identici in ciascun
     * handler, ora qui una volta sola. {@code null}/vuoto/non parsabile
     * diventano sempre {@code null} (mai un'eccezione): un campo non
     * valido semplicemente non finisce nella mappa, vedi
     * {@link #putIfPresent}.
     */
    default void putIfPresent(Map<String, Object> params, String key, Object value) {
        if (value != null) {
            params.put(key, value);
        }
    }

    /**
     * {@code value} solo se e' uno dei valori ammessi, altrimenti
     * {@code null} (la chiave viene omessa e vale il default di
     * Replicate): un valore residuo di un altro form-type (es. il
     * {@code aspect_ratio=custom} di FLUX_LORA_FF3) non deve arrivare a
     * Replicate come 422.
     */
    default String asOneOf(String value, java.util.Set<String> allowed) {
        return value != null && allowed.contains(value) ? value : null;
    }

    default Integer asInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    default Long asLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    default Double asDouble(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
