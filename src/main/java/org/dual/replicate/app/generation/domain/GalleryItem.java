package org.dual.replicate.app.generation.domain;

import java.util.List;

/**
 * Una card di galleria: UN file (immagine o video) di una generazione.
 * La tab "Tutte" mostra il primo file di ogni generazione ({@link #first}),
 * la galleria contestuale di /deep-chat TUTTI i file ({@link #allOf}), la
 * tab "Preferiti" un item per ogni file con la star.
 */
public record GalleryItem(Generation generation, String filename) {

    public static GalleryItem first(Generation generation) {
        return new GalleryItem(generation, generation.getImageFilenames().get(0));
    }

    /** Un item per ogni file della generazione, nell'ordine di {@code imageFilenames}. */
    public static List<GalleryItem> allOf(Generation generation) {
        return generation.getImageFilenames().stream().map(filename -> new GalleryItem(generation, filename)).toList();
    }

    /** True se questo file ha la star. */
    public boolean isFavourite() {
        return generation.getFavouriteFilenames().contains(filename);
    }
}
