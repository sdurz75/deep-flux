-- Immagini/video preferiti ("star") di una generazione: un sottoinsieme dei
-- file di GENERATION_IMAGE, per singolo file (una Generation puo' avere piu'
-- output). Nessun backfill: nessun file era preferito prima di questa migrazione.
CREATE TABLE "GENERATION_FAVOURITE" (
    "GENERATION_ID" BIGINT       NOT NULL,
    "FILENAME"      VARCHAR(255) NOT NULL,
    CONSTRAINT "PK_GENERATION_FAVOURITE" PRIMARY KEY ("GENERATION_ID", "FILENAME"),
    CONSTRAINT "FK_GENERATION_FAVOURITE_GENERATION" FOREIGN KEY ("GENERATION_ID")
        REFERENCES "GENERATION" ("ID") ON DELETE CASCADE
);
