package org.dual.replicate.service;

import java.util.Map;

import org.dual.replicate.domain.GenerationFormType;

/**
 * Costruisce l'input Replicate (vocabolario snake_case) per un
 * {@link GenerationFormType} a partire dai campi sottomessi (form
 * diretta di /generations o pannello impostazioni di /deep-chat, sempre
 * come stringhe: cosi' una singola implementazione serve sia un vero
 * submit HTML sia il payload JSON della chat, che le converte a stringa
 * prima di passarle qui) e sa quale fragment Thymeleaf renderizza quei
 * campi. Un'implementazione esplicita per form-type (vedi
 * {@link GenerationParameterHandlers} per il dispatch): non e' un motore
 * di schema dinamico, aggiungere una form significa aggiungere una nuova
 * implementazione, non generalizzare questa interfaccia.
 */
public interface GenerationParameterHandler {

    GenerationFormType formType();

    /** Solo i campi presenti in {@code submittedFields} finiscono nella mappa: un modello che non supporta un parametro non lo riceve. */
    Map<String, Object> toParameterMap(Map<String, String> submittedFields);

    /** Valori di default per il primo caricamento del form (nessun campo ancora sottomesso). */
    Map<String, Object> defaultFields();

    /** Selettore del fragment Thymeleaf (fragments/generation-params-*.html) che renderizza i campi di questo form-type. */
    String fragmentName();
}
