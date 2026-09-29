-- Una Generation avviata da /deep-chat ricorda la conversazione che l'ha
-- avviata (NULL per il form diretto /generations/new): serve solo a
-- ripristinare il placeholder di una generazione ancora in corso quando
-- la pagina di quella conversazione viene ricaricata.
--
-- ON DELETE SET NULL, non CASCADE: cancellare la conversazione non deve
-- eliminare le generazioni (restano in galleria, vedi V9 per lo stesso
-- ragionamento sul lato CHAT_MESSAGE).
ALTER TABLE "GENERATION" ADD COLUMN "CONVERSATION_ID" BIGINT;

ALTER TABLE "GENERATION" ADD CONSTRAINT "FK_GENERATION_CONVERSATION" FOREIGN KEY ("CONVERSATION_ID")
    REFERENCES "CHAT_CONVERSATION" ("ID") ON DELETE SET NULL;
