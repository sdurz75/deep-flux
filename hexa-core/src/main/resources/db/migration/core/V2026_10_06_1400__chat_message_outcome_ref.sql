-- Il turno di chat punta all'esito con un riferimento opaco (oggi l'id di una generazione): il nome non cita piu' il dominio della
-- generazione, cosi' la chat puo' passare al core. RENAME COLUMN mantiene la FK fk_chat_message_generation (ON DELETE SET NULL),
-- che resta una garanzia dell'app.
ALTER TABLE chat_message RENAME COLUMN generation_id TO outcome_ref;
