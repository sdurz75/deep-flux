-- La cancellazione di una Generation (galleria, singola o in blocco:
-- GenerationService#delete/#deleteAll) falliva con una violazione del
-- vincolo FK quando quell'immagine era stata generata da /deep-chat e
-- restava referenziata da un CHAT_MESSAGE (FK_CHAT_MESSAGE_GENERATION,
-- vedi V3, nessun ON DELETE dichiarato): il file immagine veniva
-- comunque rimosso da storage (ImageStorageService#delete, chiamato
-- PRIMA della riga DB in GenerationService#delete/#deleteAll) mentre la
-- riga GENERATION restava - una generazione ancora visibile in galleria
-- con tutti i suoi dettagli ma senza piu' immagine sul disco.
--
-- ON DELETE SET NULL, non CASCADE: il turno di chat resta (testo/ruolo),
-- perde solo il riferimento all'immagine ormai cancellata.
-- DeepChatService.toFiles/ChatMessageRepository.findSucceededGenerationsByConversationId
-- gestiscono gia' un generation_id null (fallback a "nessun file"/filtro
-- "m.generation is not null"), scritti in previsione proprio di questo
-- caso - nessuna modifica lato Java necessaria.
ALTER TABLE "CHAT_MESSAGE" DROP CONSTRAINT "FK_CHAT_MESSAGE_GENERATION";

ALTER TABLE "CHAT_MESSAGE" ADD CONSTRAINT "FK_CHAT_MESSAGE_GENERATION" FOREIGN KEY ("GENERATION_ID")
    REFERENCES "GENERATION" ("ID") ON DELETE SET NULL;
