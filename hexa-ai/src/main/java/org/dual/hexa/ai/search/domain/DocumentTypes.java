package org.dual.hexa.ai.search.domain;

/**
 * I limiti che valgono per tutti i documenti dell'indice e il solo tipo (metadata {@code type}) che la ricerca possiede, {@code note}: i tipi
 * delle altre sorgenti ({@code ISearchableSource#types}) sono di chi le implementa ({@code GenerationDocumentTypes}, {@code ChatDocumentTypes}).
 */
public final class DocumentTypes {

    /** Note manuali (create dalla UI /search): la riconciliazione non le crea ne' le rimuove, le timbra soltanto. */
    public static final String NOTE = "note";
    /** ~512 token del modello: oltre, il tokenizer tronca comunque. */
    public static final int MAX_CHARS = 1800;

    /**
     * Separatore fra il testo "vero" di un documento e le sue tag d'indice (vocabolario che serve solo all'embedding: tipo di media,
     * modello, orientamento...). Le tag NON sono testo per l'utente: la UI e il tool della chat mostrano {@link #visibleText}.
     */
    public static final String TAGS_SEPARATOR = "\n\n#tags: ";

    private static final String NOTE_ID_PREFIX = "note:";

    private DocumentTypes() {
    }

    /** Il testo senza le tag d'indice (invariato se non ne ha). */
    public static String visibleText(String content) {
        if (content == null) {
            return null;
        }
        int at = content.indexOf(TAGS_SEPARATOR);
        return at < 0 ? content : content.substring(0, at);
    }

    public static String newNoteId(String uuid) {
        return NOTE_ID_PREFIX + uuid;
    }

    /** Solo le note sono modificabili: i derivati hanno la fonte di verita' altrove. */
    public static boolean isNote(String id) {
        return id.startsWith(NOTE_ID_PREFIX);
    }
}
