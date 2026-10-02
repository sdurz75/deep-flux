-- File scelto fra le immagini della generazione sorgente (source_generation_id): serve a mostrare nel dettaglio la maschera di inpainting
-- sopra l'immagine su cui e' stata dipinta. NULL con una sorgente caricata, senza sorgente e per le righe precedenti.
ALTER TABLE generation ADD COLUMN source_image_filename varchar(255);
