-- Terzo modello censito: black-forest-labs/flux-krea-dev, form-type
-- diversa sia da FLUX_LORA_FF3 (fine-tune LoRA community, width/height
-- custom, lora_scale/model dev-schnell) sia da FLUX_2_KLEIN_9B (megapixels
-- a 5 valori, niente guidance/num_outputs/num_inference_steps): schema
-- reale letto via GET /v1/models/black-forest-labs/flux-krea-dev, vedi
-- Flux2Klein9bParameterHandler-simile FluxKreaDevParameterHandler.
ALTER TABLE "REPLICATE_MODEL" ALTER COLUMN "FORM_TYPE"
    ENUM('FLUX_LORA_FF3', 'FLUX_2_KLEIN_9B', 'FLUX_KREA_DEV') NOT NULL;

-- VERSION NULL: come flux-2-klein-9b, modello pubblico ufficiale Black
-- Forest Labs, lo shortcut "ultima versione" di ReplicateClient e'
-- supportato. Hash dell'ultima versione letto il 2026-09-28 via
-- GET /v1/models/black-forest-labs/flux-krea-dev per riferimento, nel
-- caso servisse pinnarlo in futuro:
-- 9d7a7c510de5b6bab1b183a9ed61cee5416ea3b4ab0bd644047780c4ef115662
INSERT INTO "REPLICATE_MODEL" ("OWNER", "NAME", "VERSION", "DESCRIPTION", "FORM_TYPE", "SORT_ORDER", "ACTIVE", "CREATED_AT")
VALUES ('black-forest-labs', 'flux-krea-dev', NULL,
        'FLUX.1 Krea [dev]: modello opinionated di Black Forest Labs/Krea per il fotorealismo',
        'FLUX_KREA_DEV', 2, TRUE, CURRENT_TIMESTAMP);
