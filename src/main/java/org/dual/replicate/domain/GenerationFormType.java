package org.dual.replicate.domain;

/**
 * Discriminatore, per modello censito in REPLICATE_MODEL (vedi
 * {@link ReplicateModel}, migrazione V6), di quale fragment/handler Java
 * gestisce i parametri di generazione per quel modello (vedi
 * org.dual.replicate.service.GenerationParameterHandler): un valore per
 * form, non un motore di schema dinamico. Aggiungere un modello con una
 * form diversa da quelle esistenti richiede una nuova costante qui (+ la
 * migrazione che estende l'ENUM della colonna FORM_TYPE), un nuovo
 * fragment fragments/generation-params-&lt;form&gt;.html e un nuovo
 * GenerationParameterHandler — nessun altro file da toccare.
 */
public enum GenerationFormType {
    FLUX_LORA_FF3,
    FLUX_2_KLEIN_9B,
    FLUX_KREA_DEV
}
