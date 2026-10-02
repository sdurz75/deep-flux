package org.dual.replicate.app.generation.application.form;

import java.util.Map;

import org.dual.replicate.app.generation.domain.GenerationFormType;
import org.dual.replicate.app.generation.port.in.IGenerationForms;
import org.dual.replicate.app.shared.domain.FormFields;

/**
 * Costruisce l'input Replicate (vocabolario snake_case) per un
 * {@link GenerationFormType} a partire dai campi sottomessi (form
 * diretta di /generations o pannello impostazioni di /deep-chat, sempre
 * come stringhe: cosi' una singola implementazione serve sia un vero
 * submit HTML sia il payload JSON della chat, che le converte a stringa
 * prima di passarle qui). Il fragment Thymeleaf che li renderizza lo sceglie l'adapter web, non l'esagono. Un'implementazione
 * esplicita per form-type (vedi {@link GenerationFormService} per il dispatch): non e' un motore
 * di schema dinamico, aggiungere una form significa aggiungere una nuova
 * implementazione, non generalizzare questa interfaccia.
 */
public interface IGenerationParameterHandler {

    GenerationFormType formType();

    /** Solo i campi presenti in {@code submittedFields} finiscono nella mappa: un modello che non supporta un parametro non lo riceve. */
    Map<String, Object> toParameterMap(Map<String, String> submittedFields);

    /** Valori di default per il primo caricamento del form (nessun campo ancora sottomesso). */
    Map<String, Object> defaultFields();

    /**
     * {@code num_outputs} limitato a 1..{@link IGenerationForms#MAX_NUM_OUTPUTS}, o {@code null} se assente/non numerico. Il {@code max} HTML non basta: il
     * pannello impostazioni di /deep-chat non passa da nessuna validazione del browser e il valore puo' arrivare da uno stato salvato.
     * Un valore fuori range e' riportato al limite (non scartato): ogni output in piu' e' a pagamento, ma Replicate rifiuterebbe comunque.
     */
    default Integer asNumOutputs(String value) {
        Integer parsed = FormFields.asInteger(value);
        return parsed == null ? null : Math.max(1, Math.min(IGenerationForms.MAX_NUM_OUTPUTS, parsed));
    }
}
