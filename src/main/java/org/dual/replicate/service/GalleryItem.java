package org.dual.replicate.service;

import org.dual.replicate.domain.Generation;

/**
 * Una card di galleria: UN file (immagine o video) di una generazione.
 * La tab "Tutte" e la galleria contestuale di /deep-chat mostrano il
 * primo file di ogni generazione ({@link #first}), la tab "Preferiti"
 * un item per ogni file con la star.
 */
public record GalleryItem(Generation generation, String filename) {

    public static GalleryItem first(Generation generation) {
        return new GalleryItem(generation, generation.getImageFilenames().get(0));
    }

    /** True se questo file ha la star. */
    public boolean isFavourite() {
        return generation.getFavouriteFilenames().contains(filename);
    }
}
