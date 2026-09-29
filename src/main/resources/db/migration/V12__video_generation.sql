-- Seconda tipologia di output: il video (prunaai/p-video, img2video/
-- text2video). Stessa tabella e stessa pipeline delle immagini: KIND dice
-- solo come renderizzare/attendere l'output. Le righe esistenti sono tutte
-- immagini (DEFAULT 'IMAGE').
ALTER TABLE "GENERATION" ADD COLUMN "KIND" ENUM('IMAGE', 'VIDEO') NOT NULL DEFAULT 'IMAGE';

-- Generazione sorgente di un img2video ("Anima"). ON DELETE SET NULL, non
-- CASCADE: cancellare l'immagine originale non deve eliminare il video
-- derivato (stesso ragionamento di V9/V11).
ALTER TABLE "GENERATION" ADD COLUMN "SOURCE_GENERATION_ID" BIGINT;

ALTER TABLE "GENERATION" ADD CONSTRAINT "FK_GENERATION_SOURCE" FOREIGN KEY ("SOURCE_GENERATION_ID")
    REFERENCES "GENERATION" ("ID") ON DELETE SET NULL;

ALTER TABLE "REPLICATE_MODEL" ALTER COLUMN "FORM_TYPE"
    ENUM('FLUX_LORA_FF3', 'FLUX_2_KLEIN_9B', 'FLUX_KREA_DEV', 'P_VIDEO') NOT NULL;

-- VERSION NULL: modello ufficiale (is_official=true via GET
-- /v1/models/prunaai/p-video), lo shortcut "ultima versione" e' supportato.
-- Hash dell'ultima versione letto il 2026-09-29, per riferimento:
-- 50e52eaadc2a1648c1c4f854695c2ac7d56c23068802e8cafb8b69f1100b9ece
-- SORT_ORDER 3: il modello di default (primo attivo) resta un modello immagine.
INSERT INTO "REPLICATE_MODEL" ("OWNER", "NAME", "VERSION", "DESCRIPTION", "FORM_TYPE", "SORT_ORDER", "ACTIVE", "CREATED_AT")
VALUES ('prunaai', 'p-video', NULL,
        'P-Video (Pruna AI): video da testo o da immagine, con modalita'' draft',
        'P_VIDEO', 3, TRUE, CURRENT_TIMESTAMP);
