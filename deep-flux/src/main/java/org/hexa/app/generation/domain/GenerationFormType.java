package org.hexa.app.generation.domain;

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
 * (img2img/inpainting col fine-tune, vedi {@link #maskRequired()}). {@link #sourceRequired()}
 * distingue i modelli che SENZA un'immagine sorgente non hanno senso (kontext, flux-fill-*: l'output eredita
 * dimensione e composizione dalla sorgente) da quelli dove e' opzionale o assente: compaiono nel combobox
 * di /generations/new ma non in /deep-chat, che non puo' fornirla.
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
    private final boolean sourceRequired;

    GenerationFormType(GenerationKind kind, String sourceImageParam, String maskParam, boolean sourceRequired) {
        this.kind = kind;
        this.sourceImageParam = sourceImageParam;
        this.maskParam = maskParam;
        this.sourceRequired = sourceRequired;
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

    /** True se il modello accetta una maschera di inpainting (bianco = zona da ridipingere), obbligatoria o no ({@link #maskRequired()}). */
    public boolean takesMask() {
        return maskParam != null;
    }

    /**
     * True se la maschera e' OBBLIGATORIA: i modelli di inpainting puri (flux-fill-*), dove la sorgente e' obbligatoria e la maschera
     * dice cosa ridipingere. Per gli altri che la prendono (flux-lora-finetune, text-to-image) e' opzionale: senza e' una normale
     * generazione; con una maschera serve anche la sorgente.
     */
    public boolean maskRequired() {
        return takesMask() && sourceRequired;
    }

    /**
     * True se la sorgente e' OBBLIGATORIA (kontext, flux-fill-*): senza immagine il modello non ha senso e {@code GenerationService#create}
     * rifiuta prima di chiamare Replicate. Non compaiono in /deep-chat (la chat non ha una sorgente da dare).
     */
    public boolean sourceRequired() {
        return sourceRequired;
    }

    /**
     * True se la bozza del prompt e' un'ISTRUZIONE di modifica ("cambia X in Y"): sorgente obbligatoria e nessuna maschera (kontext).
     * Con la maschera il prompt descrive la sola zona dipinta ({@link #maskRequired()}).
     */
    public boolean isInstructionEdit() {
        return sourceRequired && !takesMask();
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
