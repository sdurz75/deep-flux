-- Il form-type di flux-lora-ff3 diventa generico: vale per ogni LoRA addestrato su Replicate (stesso schema di input). Un altro fine-tune si
-- censisce con un INSERT in replicate_model (version = hash pinnato, form_type = 'FLUX_LORA_FINETUNE'), senza codice.
UPDATE replicate_model SET form_type = 'FLUX_LORA_FINETUNE' WHERE form_type = 'FLUX_LORA_FF3';
