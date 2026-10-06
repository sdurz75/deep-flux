package org.dual.replicate.app.training.domain;

/**
 * Un'immagine dello zip: il file nello storage ({@code filename}) col nome che avra' nello zip ({@code imageEntry}) e la sua didascalia nel {@code .txt}
 * accanto ({@code captionEntry}, stesso nome).
 */
public record ArchiveItem(String imageEntry, String filename, String captionEntry, String caption) {
}
