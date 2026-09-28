-- Secondo modello censito: black-forest-labs/flux-2-klein-9b, form-type
-- diversa da FLUX_LORA_FF3 (vedi Flux2Klein9bParameterHandler), quindi
-- l'ENUM di FORM_TYPE va esteso prima di poter censire la riga.
ALTER TABLE "REPLICATE_MODEL" ALTER COLUMN "FORM_TYPE" ENUM('FLUX_LORA_FF3', 'FLUX_2_KLEIN_9B') NOT NULL;

-- VERSION NULL: a differenza di sdurz75/flux-lora-ff3, questo e' un
-- modello pubblico ufficiale Black Forest Labs, per cui lo shortcut
-- "ultima versione" di ReplicateClient#createPrediction
-- (/models/{owner}/{name}/predictions) e' supportato. Hash dell'ultima
-- versione letto il 2026-09-28 via GET /v1/models/black-forest-labs/flux-2-klein-9b
-- per riferimento, nel caso servisse pinnarlo in futuro:
-- 963f7b2c4aa2bc7e6377b95759dcf3a21cf175f6e8b0d8c1efe7bf6c8a23b690
INSERT INTO "REPLICATE_MODEL" ("OWNER", "NAME", "VERSION", "DESCRIPTION", "FORM_TYPE", "SORT_ORDER", "ACTIVE", "CREATED_AT")
VALUES ('black-forest-labs', 'flux-2-klein-9b', NULL,
        'FLUX.2 klein 9B: modello foundation distillato (4 step), inferenza rapida',
        'FLUX_2_KLEIN_9B', 1, TRUE, CURRENT_TIMESTAMP);
