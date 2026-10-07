package org.dual.hexa.core.backup.port.out;

import java.io.InputStream;

import org.dual.hexa.core.backup.domain.TableInfo;

/** Scrittura del DB per l'import: schema (Flyway) e caricamento dei dati. */
public interface IDatabaseRestore {

    /** {@code true} se il database non ha ancora lo schema dell'app (nessuna cronologia di Flyway). */
    boolean isVirgin();

    /** {@code true} se questo jar ha una migrazione con quella versione. */
    boolean knowsSchemaVersion(String version);

    /** Cancella tutto lo schema corrente e lo ricrea vuoto (CANCELLA i dati). */
    void wipe();

    /** Applica le migrazioni fino a {@code version} compresa. */
    void migrateTo(String version);

    /** Applica tutte le migrazioni ancora mancanti. */
    void migrateToLatest();

    /** Apre la transazione di caricamento (tutto o niente). */
    Load beginLoad();

    interface Load extends AutoCloseable {

        /** Svuota tutte le tabelle dati (le migrazioni seminano alcune righe, es. il catalogo modelli). */
        void truncateAll();

        /** Carica {@code data} (formato testo {@code COPY}) nella tabella; ritorna le righe. */
        long copyIn(TableInfo table, InputStream data);

        /** Riallinea ogni sequenza identity al massimo id caricato: senza, il primo insert dopo il ripristino collide. */
        void resetSequences();

        void commit();

        /** Senza {@link #commit} annulla tutto. */
        @Override
        void close();
    }
}
