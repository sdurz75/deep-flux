-- Il registro errori (V17) diventa il registro generico degli eventi di sistema: qui solo il rename
-- (comportamento invariato), severita'/subject/visualizzato arrivano con V22. Nessuna FK verso APP_ERROR.
ALTER TABLE "APP_ERROR" RENAME TO "SYSTEM_EVENT";
ALTER TABLE "SYSTEM_EVENT" RENAME CONSTRAINT "PK_APP_ERROR" TO "PK_SYSTEM_EVENT";
ALTER INDEX "IDX_APP_ERROR_LAST_SEEN" RENAME TO "IDX_SYSTEM_EVENT_LAST_SEEN";
