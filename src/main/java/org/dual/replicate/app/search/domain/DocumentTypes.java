package org.dual.replicate.app.search.domain;

/** I tipi di documento dell'indice (metadata {@code type}) e i limiti che valgono per tutti. */
public final class DocumentTypes {

    public static final String GENERATION = "generation";
    public static final String CHAT = "chat";
    public static final String CONVERSATION = "conversation";
    /** Note manuali (create dalla UI /search): la riconciliazione non le crea ne' le rimuove, le timbra soltanto. */
    public static final String NOTE = "note";
    /** ~512 token del modello: oltre, il tokenizer tronca comunque. */
    public static final int MAX_CHARS = 1800;

    private static final String NOTE_ID_PREFIX = "note:";

    private DocumentTypes() {
    }

    public static String newNoteId(String uuid) {
        return NOTE_ID_PREFIX + uuid;
    }

    /** Solo le note sono modificabili: i derivati hanno la fonte di verita' altrove. */
    public static boolean isNote(String id) {
        return id.startsWith(NOTE_ID_PREFIX);
    }
}
