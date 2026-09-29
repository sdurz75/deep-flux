-- Costo stimato (USD) di una generazione completata, calcolato al
-- completamento da ReplicatePricing a partire dai metrics della prediction
-- (l'API Replicate non espone il prezzo) e salvato come snapshot: un cambio
-- di prezzo futuro non riscrive lo storico. NULL per le righe precedenti a
-- questa migrazione (i metrics non erano salvati: nessun backfill, come V8
-- per il seed), per le generazioni fallite e per i modelli senza regola.
ALTER TABLE "GENERATION" ADD COLUMN "COST_USD" DECIMAL(12, 6);
