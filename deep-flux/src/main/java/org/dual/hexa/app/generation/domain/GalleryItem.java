package org.dual.hexa.app.generation.domain;

import java.util.List;

/**
 * Una card di galleria: UN file (immagine o video) di una generazione.
 * Ogni galleria (tab "Tutte", "Importate" e "Preferiti", selettore
 * dell'archivio, galleria contestuale di /deep-chat) mostra un item per file,
 * mai raggruppati per generazione.
 */
public record GalleryItem(Generation generation, String filename) {

    /** Un item per ogni file della generazione, nell'ordine di {@code imageFilenames}. */
    public static List<GalleryItem> allOf(Generation generation) {
        return generation.getImageFilenames().stream().map(filename -> new GalleryItem(generation, filename)).toList();
    }

    /** True se questo file ha la star. */
    public boolean isFavourite() {
        return generation.getFavouriteFilenames().contains(filename);
    }
}
