-- Immagine di partenza caricata dall'utente per un img2video stand-alone
-- (nome file sotto storage.images-dir, "upload-<uuid>.<ext>"). Non e' una
-- Generation: non compare in /gallery ne' in /generations. NULL per tutte le
-- righe preesistenti e per le generazioni senza upload (incluse quelle
-- animate da una Generation esistente, tracciate da SOURCE_GENERATION_ID).
ALTER TABLE "GENERATION" ADD COLUMN "SOURCE_UPLOAD_FILENAME" VARCHAR(255);
