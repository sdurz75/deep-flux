-- Sesto form-type: black-forest-labs/flux-dev-lora (FLUX.1 [dev] con LoRA caricabili al volo:
-- lora_weights/extra_lora + scale, token HF/Civitai, img2img opzionale con image/prompt_strength).
-- Output = immagini (KIND IMAGE), sorgente opzionale (vedi GenerationFormType#sourceImageParam).
-- Schema reale letto il 2026-10-01 via GET /v1/models/black-forest-labs/flux-dev-lora.
ALTER TABLE "REPLICATE_MODEL" ALTER COLUMN "FORM_TYPE"
    ENUM('FLUX_LORA_FF3', 'FLUX_2_KLEIN_9B', 'FLUX_KREA_DEV', 'P_VIDEO', 'FLUX_KONTEXT_DEV', 'FLUX_DEV_LORA') NOT NULL;

-- VERSION NULL: modello ufficiale (is_official=true), lo shortcut "ultima versione" e' supportato.
-- Hash dell'ultima versione al 2026-10-01, per riferimento:
-- ae0d7d645446924cf1871e3ca8796e8318f72465d2b5af9323a835df93bf0917
-- SORT_ORDER 5: il default (primo attivo) resta un modello text-to-image.
INSERT INTO "REPLICATE_MODEL" ("OWNER", "NAME", "VERSION", "DESCRIPTION", "FORM_TYPE", "SORT_ORDER", "ACTIVE", "CREATED_AT")
VALUES ('black-forest-labs', 'flux-dev-lora', NULL,
        'FLUX.1 [dev] con LoRA caricabili al volo (Replicate, HuggingFace, CivitAI, .safetensors), anche img2img',
        'FLUX_DEV_LORA', 5, TRUE, CURRENT_TIMESTAMP);
