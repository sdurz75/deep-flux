-- Inpainting (flux-fill-dev, modello di modifica: sorgente + maschera + LoRA singolo). sort_order 6: kontext (4) resta il modello
-- preselezionato nella pagina di modifica.
INSERT INTO replicate_model (owner, name, version, description, form_type, sort_order, active, created_at) VALUES
    ('black-forest-labs', 'flux-fill-dev', NULL, 'FLUX.1 Fill [dev]: inpainting di una zona dipinta a mano, con un LoRA opzionale', 'FLUX_FILL_DEV', 6, TRUE, CURRENT_TIMESTAMP);

-- Maschera di inpainting caricata dall'utente (file nello storage binari, come source_upload_filename): si elimina con la generazione.
ALTER TABLE generation ADD COLUMN mask_upload_filename varchar(255);
