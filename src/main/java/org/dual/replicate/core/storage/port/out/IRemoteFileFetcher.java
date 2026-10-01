package org.dual.replicate.core.storage.port.out;

import java.io.IOException;
import java.io.InputStream;

/** Scarica un file da un URL (l'output di una prediction) in streaming, con retry dei soli errori transitori. */
public interface IRemoteFileFetcher {

    /** Consuma il corpo della risposta; un'eccezione fa ritentare l'intero download se transitoria. */
    @FunctionalInterface
    interface Sink {
        void accept(InputStream body) throws IOException;
    }

    /**
     * GET {@code sourceUrl} passando il corpo a {@code sink}. {@code targetName} serve solo ai messaggi d'errore.
     *
     * @throws org.dual.replicate.core.storage.domain.StorageException se il download non riesce (kind secondo l'errore HTTP)
     */
    void fetch(String sourceUrl, String targetName, Sink sink);
}
