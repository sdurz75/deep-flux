-- Quarto tipo di form: modifica di un'immagine esistente
-- (black-forest-labs/flux-kontext-dev). Output = immagine (KIND resta IMAGE),
-- ma input_image e' obbligatorio: non va mescolato ai modelli text-to-image
-- (vedi GenerationFormType#isEdit e ReplicateModelCatalog#editModels).
-- Schema reale letto da replicate.com/black-forest-labs/flux-kontext-dev/api/schema.
ALTER TABLE "REPLICATE_MODEL" ALTER COLUMN "FORM_TYPE"
    ENUM('FLUX_LORA_FF3', 'FLUX_2_KLEIN_9B', 'FLUX_KREA_DEV', 'P_VIDEO', 'FLUX_KONTEXT_DEV') NOT NULL;

-- VERSION NULL: modello ufficiale (is_official=true), lo shortcut "ultima
-- versione" e' supportato. SORT_ORDER 4: il default (primo attivo) resta un
-- modello text-to-image.
INSERT INTO "REPLICATE_MODEL" ("OWNER", "NAME", "VERSION", "DESCRIPTION", "FORM_TYPE", "SORT_ORDER", "ACTIVE", "CREATED_AT")
VALUES ('black-forest-labs', 'flux-kontext-dev', NULL,
        'FLUX.1 Kontext [dev]: modifica un''immagine esistente a partire da un prompt',
        'FLUX_KONTEXT_DEV', 4, TRUE, CURRENT_TIMESTAMP);
