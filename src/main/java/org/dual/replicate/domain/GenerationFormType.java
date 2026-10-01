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
 * {@link GenerationKind} del media che produce e, se prende un'immagine
 * sorgente, la chiave Replicate sotto cui va inviata ({@link #sourceImageParam()}:
 * "image" per p-video e flux-dev-lora, "input_image" per kontext-dev). {@link #isEdit()}
 * distingue i modelli di modifica (sorgente obbligatoria, output immagine)
 * dai text-to-image: hanno la loro pagina e non compaiono ne' nel combobox
 * delle immagini ne' in /deep-chat.
 */
public enum GenerationFormType {
    FLUX_LORA_FF3(GenerationKind.IMAGE, null, false),
    FLUX_2_KLEIN_9B(GenerationKind.IMAGE, null, false),
    FLUX_KREA_DEV(GenerationKind.IMAGE, null, false),
    P_VIDEO(GenerationKind.VIDEO, "image", false),
    FLUX_KONTEXT_DEV(GenerationKind.IMAGE, "input_image", true),
    FLUX_DEV_LORA(GenerationKind.IMAGE, "image", false);

    private final GenerationKind kind;
    private final String sourceImageParam;
    private final boolean edit;

    GenerationFormType(GenerationKind kind, String sourceImageParam, boolean edit) {
        this.kind = kind;
        this.sourceImageParam = sourceImageParam;
        this.edit = edit;
    }

    public GenerationKind kind() {
        return kind;
    }

    /** Chiave dell'input Replicate per l'immagine sorgente, o null se il modello non ne prende. */
    public String sourceImageParam() {
        return sourceImageParam;
    }

    /** True per i modelli di modifica immagine: sorgente obbligatoria. */
    public boolean isEdit() {
        return edit;
    }

    /** True se il modello accetta un'immagine sorgente (img2video, modifica o img2img opzionale). */
    public boolean takesSourceImage() {
        return sourceImageParam != null;
    }
}
