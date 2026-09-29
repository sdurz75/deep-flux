package org.dual.replicate.domain;

/**
 * Discriminatore, per modello censito in REPLICATE_MODEL (vedi
 * {@link ReplicateModel}, migrazione V6), di quale fragment/handler Java
 * gestisce i parametri di generazione per quel modello (vedi
 * org.dual.replicate.service.GenerationParameterHandler): un valore per
 * form, non un motore di schema dinamico. Aggiungere un modello con una
 * form diversa da quelle esistenti richiede una nuova costante qui (+ la
 * migrazione che estende l'ENUM della colonna FORM_TYPE), un nuovo
 * fragment fragments/generation-params-&lt;form&gt;.html, un nuovo
 * GenerationParameterHandler e un nuovo {@code th:case} nel guscio
 * fragments/generation-params.html. Ogni form-type dichiara anche il
 * {@link GenerationKind} del media che produce.
 */
public enum GenerationFormType {
    FLUX_LORA_FF3(GenerationKind.IMAGE),
    FLUX_2_KLEIN_9B(GenerationKind.IMAGE),
    FLUX_KREA_DEV(GenerationKind.IMAGE),
    P_VIDEO(GenerationKind.VIDEO);

    private final GenerationKind kind;

    GenerationFormType(GenerationKind kind) {
        this.kind = kind;
    }

    public GenerationKind kind() {
        return kind;
    }
}
