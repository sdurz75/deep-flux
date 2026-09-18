-- La chat di rifinitura prompt persistita (ChatConversation/ChatMessage,
-- pagine /chat, /chat/{id}) e' stata rimossa: lo sviluppo si concentra
-- su /deep-chat (generazione immagini assistita da chatbot). Le tabelle
-- non hanno piu' un'entity JPA a fronte, vanno rimosse invece di restare
-- come stato orfano nel DB (child prima del parent per il vincolo FK).
DROP TABLE "CHAT_MESSAGE";
DROP TABLE "CHAT_CONVERSATION";
