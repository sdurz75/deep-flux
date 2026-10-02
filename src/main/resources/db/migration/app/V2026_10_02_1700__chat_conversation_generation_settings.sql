-- Stato GREZZO del form di generazione di /deep-chat (JSON: modello e parametri, come li salva localStorage) per conversazione.
-- NULL = conversazione precedente a questa migrazione (alla prima apertura adotta il form del browser e lo salva qui);
-- '{}' = "default del catalogo" (ogni conversazione nuova, la scrive il costruttore dell'entity).
ALTER TABLE chat_conversation ADD COLUMN generation_settings_json text;
