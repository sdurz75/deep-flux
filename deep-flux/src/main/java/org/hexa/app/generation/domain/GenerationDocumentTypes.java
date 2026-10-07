package org.hexa.app.generation.domain;

/** I tipi di documento dell'indice di ricerca posseduti dalle generazioni (metadata {@code type}, vedi {@code GenerationSearchSource}). */
public final class GenerationDocumentTypes {

    public static final String GENERATION = "generation";
    /** Immagini esterne importate (non generate): stessa sorgente dati di {@code generation}, ma un tipo proprio per la UI e i filtri. */
    public static final String IMPORTED = "imported";

    private GenerationDocumentTypes() {
    }
}
