-- Tag utente (V. CLAUDE.md "Tag"): per generazione intera, per singolo file (i tag delle conversazioni di /deep-chat sono in hexa-ai, db/migration/ai).
-- Distinti da generation.analysis_tags (tag dell'analisi AI delle immagini importate).
CREATE TABLE generation_tag (
    generation_id bigint       NOT NULL,
    tag           varchar(255) NOT NULL,
    CONSTRAINT pk_generation_tag PRIMARY KEY (generation_id, tag),
    CONSTRAINT fk_generation_tag_generation FOREIGN KEY (generation_id) REFERENCES generation (id) ON DELETE CASCADE
);
CREATE INDEX idx_generation_tag_tag ON generation_tag (tag);

CREATE TABLE generation_file_tag (
    generation_id bigint       NOT NULL,
    filename      varchar(255) NOT NULL,
    tag           varchar(255) NOT NULL,
    CONSTRAINT pk_generation_file_tag PRIMARY KEY (generation_id, filename, tag),
    CONSTRAINT fk_generation_file_tag_generation FOREIGN KEY (generation_id) REFERENCES generation (id) ON DELETE CASCADE
);
CREATE INDEX idx_generation_file_tag_tag ON generation_file_tag (tag);
