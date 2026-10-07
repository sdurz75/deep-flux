-- Token API di default di un LoRA anagrafato (privato su HuggingFace/CivitAI): la form di generazione lo preseleziona alla scelta del
-- preset. Riferimento per ID (mai il segreto); cancellare il token scollega il preset.
ALTER TABLE lora_preset ADD COLUMN default_token_id bigint;
ALTER TABLE lora_preset ADD CONSTRAINT fk_lora_preset_default_token
    FOREIGN KEY (default_token_id) REFERENCES api_token (id) ON DELETE SET NULL;
