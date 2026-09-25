-- Una generazione puo' produrre piu' di un'immagine per richiesta (nuovo
-- parametro num_outputs, 1-8): IMAGE_FILENAME (colonna scalare, una
-- sola immagine) diventa una tabella figlia ordinata, mappata da
-- Generation con @ElementCollection (un elenco di nomi file senza
-- identita'/comportamento proprio: non serve una entity JPA dedicata).
-- A differenza di V2 (che eliminava lo stato di una feature ormai
-- rimossa), qui la galleria e' stato vivo dell'utente: i dati esistenti
-- vengono copiati come primo elemento (ORDINAL 0) prima di rimuovere la
-- colonna, non semplicemente scartati.
CREATE TABLE "GENERATION_IMAGE" (
    "GENERATION_ID" BIGINT NOT NULL,
    "ORDINAL"       INT NOT NULL,
    "FILENAME"      VARCHAR(255) NOT NULL,
    CONSTRAINT "PK_GENERATION_IMAGE" PRIMARY KEY ("GENERATION_ID", "ORDINAL"),
    CONSTRAINT "FK_GENERATION_IMAGE_GENERATION" FOREIGN KEY ("GENERATION_ID")
        REFERENCES "GENERATION" ("ID")
);

INSERT INTO "GENERATION_IMAGE" ("GENERATION_ID", "ORDINAL", "FILENAME")
SELECT "ID", 0, "IMAGE_FILENAME" FROM "GENERATION" WHERE "IMAGE_FILENAME" IS NOT NULL;

ALTER TABLE "GENERATION" DROP COLUMN "IMAGE_FILENAME";
