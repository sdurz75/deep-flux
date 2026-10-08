-- I token sono ora «segreti» (vedi core V2026_10_08_1600): le colonne dell'app che li referenziano seguono il nome. La FK di lora_preset segue il rename della tabella.
ALTER TABLE lora_preset RENAME COLUMN default_token_id TO default_secret_id;
ALTER TABLE training_dataset RENAME COLUMN hf_token_id TO hf_secret_id;
