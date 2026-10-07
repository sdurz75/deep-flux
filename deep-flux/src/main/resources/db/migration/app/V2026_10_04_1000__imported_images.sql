-- Immagini esterne nell'archivio: una riga di generation con origin = 'IMPORTED' (nessuna prediction, nessun modello).
-- external_id e model restano valorizzati per le generate; per le importate sono NULL.
ALTER TABLE generation ALTER COLUMN external_id DROP NOT NULL;
ALTER TABLE generation ALTER COLUMN model DROP NOT NULL;
ALTER TABLE generation ADD COLUMN origin varchar(10) NOT NULL DEFAULT 'GENERATED';
-- Stato dell'analisi di contenuto (modello di visione), solo per le importate: PENDING | DONE | FAILED. La descrizione sta in prompt.
ALTER TABLE generation ADD COLUMN analysis_status varchar(10);
-- Tag dell'analisi (separati da virgola), vocabolario d'indice it/en.
ALTER TABLE generation ADD COLUMN analysis_tags text;
