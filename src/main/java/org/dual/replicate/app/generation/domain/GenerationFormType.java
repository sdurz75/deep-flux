package org.dual.replicate.app.generation.domain;

/**
 * Discriminatore, per modello censito in REPLICATE_MODEL (vedi
 * {@link ReplicateModel}, migrazione V6), di quale fragment/handler Java
 * gestisce i parametri di generazione per quel modello (vedi
 * {@code IGenerationParameterHandler} in application.form): un valore per
 * form, non un motore di schema dinamico. Aggiungere un modello con una
 * form diversa da quelle esistenti richiede una nuova costante qui (+ la
 * migrazione che censisce il modello: FORM_TYPE e' un varchar, niente ENUM di DB), un nuovo
 * fragment fragments/generation-params-&lt;form&gt;.html, un nuovo
 * IGenerationParameterHandler e un nuovo {@code th:case} nel guscio
 * fragments/app/generation-params.html. Ogni form-type dichiara anche il
 * {@link GenerationKind} del media che produce e, se prende un'immagine
 * sorgente, la chiave Replicate sotto cui va inviata ({@link #sourceImageParam()}:
 * "image" per p-video, flux-dev-lora, flux-fill-dev e flux-fill-pro, "input_image" per kontext-dev) e, per l'inpainting,
 * quella della maschera ({@link #maskParam()}); flux-lora-finetune ha entrambe ma e' un text-to-image: sorgente e maschera sono OPZIONALI
 * (img2img/inpainting col fine-tune, vedi {@link #requiresMask()}). {@link #isEdit()}
 * distingue i modelli di modifica (sorgente obbligatoria, output immagine)
 * dai text-to-image: hanno la loro pagina e non compaiono ne' nel combobox
 * delle immagini ne' in /deep-chat.
 */
public enum GenerationFormType {
    FLUX_LORA_FINETUNE(GenerationKind.IMAGE, "image", "mask", false),
    FLUX_2_KLEIN_9B(GenerationKind.IMAGE, null, null, false),
    FLUX_KREA_DEV(GenerationKind.IMAGE, null, null, false),
    P_VIDEO(GenerationKind.VIDEO, "image", null, false),
    FLUX_KONTEXT_DEV(GenerationKind.IMAGE, "input_image", null, true),
    FLUX_DEV_LORA(GenerationKind.IMAGE, "image", null, false),
    FLUX_FILL_DEV(GenerationKind.IMAGE, "image", "mask", true),
    FLUX_FILL_PRO(GenerationKind.IMAGE, "image", "mask", true);

    private final GenerationKind kind;
    private final String sourceImageParam;
    private final String maskParam;
    private final boolean edit;

    GenerationFormType(GenerationKind kind, String sourceImageParam, String maskParam, boolean edit) {
        this.kind = kind;
        this.sourceImageParam = sourceImageParam;
        this.maskParam = maskParam;
        this.edit = edit;
    }

    public GenerationKind kind() {
        return kind;
    }

    /** Chiave dell'input Replicate per l'immagine sorgente, o null se il modello non ne prende. */
    public String sourceImageParam() {
        return sourceImageParam;
    }

    /** Chiave dell'input Replicate per la maschera di inpainting, o null se il modello non ne prende. */
    public String maskParam() {
        return maskParam;
    }

    /** True se il modello accetta una maschera di inpainting (bianco = zona da ridipingere), obbligatoria o no ({@link #requiresMask()}). */
    public boolean takesMask() {
        return maskParam != null;
    }

    /**
     * True se la maschera e' OBBLIGATORIA: i modelli di inpainting puri (flux-fill-*, di modifica). Per gli altri che la prendono
     * (flux-lora-finetune, text-to-image) e' opzionale: senza e' una normale generazione; con una maschera serve anche la sorgente.
     */
    public boolean requiresMask() {
        return takesMask() && edit;
    }

    /** True per i modelli di modifica immagine: sorgente obbligatoria. */
    public boolean isEdit() {
        return edit;
    }

    /**
     * True se il modello ha l'input {@code disable_safety_checker}, che {@code GenerationService} forza per ogni immagine. flux-fill-pro
     * non ce l'ha: ha {@code safety_tolerance} ({@link #safetyToleranceParam()}), e inviare un input sconosciuto a Replicate sarebbe un rischio inutile.
     */
    public boolean hasDisableSafetyChecker() {
        return this != FLUX_FILL_PRO;
    }

    /**
     * Chiave dell'input Replicate per la tolleranza del controllo di sicurezza, o null se il modello usa {@code disable_safety_checker}.
     * Come quel flag NON e' un campo del form: la forza {@code GenerationService} al massimo (il piu' permissivo), l'utente non la vede.
     */
    public String safetyToleranceParam() {
        return this == FLUX_FILL_PRO ? "safety_tolerance" : null;
    }

    /** True se il modello accetta un'immagine sorgente (img2video, modifica o img2img opzionale). */
    public boolean takesSourceImage() {
        return sourceImageParam != null;
    }
}
