package org.hexa.app.training.domain;

import java.io.IOException;
import java.io.InputStream;

/**
 * I pesi di un training riuscito, il solo file {@code .safetensors} (per un LoRA: qualche centinaio di MB), come lo lascia il trainer su Replicate. Il contenuto NON
 * e' in memoria ne' su disco: ogni {@link #read} lo scarica di nuovo, dall'inizio. Chi lo carica altrove ne ha bisogno due volte (prima l'impronta SHA-256, poi
 * l'invio), quindi lo legge due volte.
 */
public interface WeightsFile {

    /** Il nome del file, senza cartelle: quello che avra' nel repo. */
    String name();

    /** Dimensione in byte. */
    long size();

    /**
     * Scarica il file e lo passa al lettore (esattamente {@link #size()} byte); lo stream si chiude da solo quando il lettore ritorna o fallisce. Un guasto del
     * download e' una {@code RemoteServiceException}; il lettore puo' lanciarne una sua, che passa com'e'.
     */
    <T> T read(Reader<T> reader);

    @FunctionalInterface
    interface Reader<T> {

        T read(InputStream in) throws IOException;
    }
}
