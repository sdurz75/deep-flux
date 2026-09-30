-- Turno ASSISTANT di errore di /deep-chat: scritto quando la chiamata LLM fallisce, cosi' la
-- cronologia non resta con un turno USER senza risposta. Escluso dal contesto inviato all'LLM.
ALTER TABLE "CHAT_MESSAGE" ADD COLUMN "ERROR" BOOLEAN DEFAULT FALSE NOT NULL;
