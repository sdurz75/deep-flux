-- Inpainting a qualita' piu' alta (flux-fill-pro, modello di modifica: sorgente + maschera, senza LoRA). sort_order 7: kontext (4) resta preselezionato.
INSERT INTO replicate_model (owner, name, version, description, form_type, sort_order, active, created_at) VALUES
    ('black-forest-labs', 'flux-fill-pro', NULL, 'FLUX.1 Fill [pro]: inpainting di una zona dipinta a mano, qualita'' massima (senza LoRA)', 'FLUX_FILL_PRO', 7, TRUE, CURRENT_TIMESTAMP);
