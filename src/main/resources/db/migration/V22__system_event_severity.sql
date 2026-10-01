-- Il registro eventi di sistema (V21) distingue ERROR (gli errori di prima) da WARNING (es. scadenza dei token),
-- sa a cosa si riferisce un evento (SUBJECT, es. 'token:12', parte della chiave di serie dei warning) e se e' stato
-- visualizzato (ACKNOWLEDGED_AT, per la campanella della toolbar; lato server: vale per tutte le tab/browser).
-- SEVERITY come VARCHAR (non ENUM H2) per non richiedere una migrazione a ogni nuovo livello.
ALTER TABLE "SYSTEM_EVENT" ADD COLUMN "SEVERITY" VARCHAR(10) DEFAULT 'ERROR' NOT NULL;
ALTER TABLE "SYSTEM_EVENT" ADD COLUMN "SUBJECT" VARCHAR(100);
ALTER TABLE "SYSTEM_EVENT" ADD COLUMN "ACKNOWLEDGED_AT" TIMESTAMP(6) WITH TIME ZONE;

-- Lo storico esistente conta come gia' visualizzato: la campanella non deve accendersi con tutto il passato.
UPDATE "SYSTEM_EVENT" SET "ACKNOWLEDGED_AT" = "LAST_SEEN_AT";

CREATE INDEX "IDX_SYSTEM_EVENT_SEVERITY" ON "SYSTEM_EVENT" ("SEVERITY", "LAST_SEEN_AT");
CREATE INDEX "IDX_SYSTEM_EVENT_UNSEEN" ON "SYSTEM_EVENT" ("ACKNOWLEDGED_AT", "LAST_SEEN_AT");
