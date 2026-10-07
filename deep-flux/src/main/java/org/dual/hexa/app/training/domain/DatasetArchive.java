package org.dual.hexa.app.training.domain;

import java.io.InputStream;

/**
 * Lo zip del dataset pronto da caricare ({@code input_images} del trainer): le immagini e un {@code .txt} di didascalia per ciascuna, con lo stesso nome. Il file
 * sta in un'area temporanea dell'adapter che lo ha costruito: {@link #open()} si puo' chiamare piu' volte (un caricamento ritentato rilegge dall'inizio) e
 * {@link #close()} lo elimina. Senza dipendenze dal file system: lo use case non lo tocca.
 */
public interface DatasetArchive extends AutoCloseable {

    /** Dimensione in byte. */
    long size();

    /** Un nuovo stream dall'inizio; chi lo chiede lo chiude. */
    InputStream open();

    @Override
    void close();
}
