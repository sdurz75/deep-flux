-- Addestramento LoRA, tappa 5: il risultato di un training riuscito. Il preset di /loras si crea in background (preset_id, gia' presente), e il modello Replicate
-- di destinazione deve risultare utilizzabile nell'app (censito come fine-tune): lo stato dice se e' fatto (REGISTERED), da rifare (PENDING) o impossibile
-- (REJECTED: lo schema della versione non ha lora_scale, o il modello e' disattivato). Serve a non ritentare all'infinito un modello che non si puo' censire.
ALTER TABLE training ADD COLUMN model_status varchar(20) NOT NULL DEFAULT 'PENDING';
