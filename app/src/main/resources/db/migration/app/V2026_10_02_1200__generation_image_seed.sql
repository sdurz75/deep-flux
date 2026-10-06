-- Seed per singolo file di una generazione, quando i log della prediction ne riportano uno per output
-- (altrimenti la riga manca e vale generation.seed, il seed del batch).
CREATE TABLE generation_image_seed (
    generation_id bigint NOT NULL,
    filename      varchar(255) NOT NULL,
    seed          bigint NOT NULL,
    CONSTRAINT pk_generation_image_seed PRIMARY KEY (generation_id, filename),
    CONSTRAINT fk_generation_image_seed_generation FOREIGN KEY (generation_id) REFERENCES generation (id) ON DELETE CASCADE
);
