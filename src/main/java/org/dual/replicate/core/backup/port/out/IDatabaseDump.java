package org.dual.replicate.core.backup.port.out;

import java.io.OutputStream;
import java.util.List;
import java.util.Set;

import org.dual.replicate.core.backup.domain.BlobColumn;
import org.dual.replicate.core.backup.domain.TableInfo;

/** Lettura del DB per l'export: tutto dentro UNO snapshot coerente, senza scrivere nulla. */
public interface IDatabaseDump {

    /** Apre lo snapshot (transazione REPEATABLE READ, sola lettura); chiuderlo lo termina. */
    Snapshot open();

    interface Snapshot extends AutoCloseable {

        /** Ultima versione dello schema applicata (Flyway), senza migrare nulla. @throws org.dual.replicate.core.backup.domain.BackupException se lo schema non c'e' */
        String schemaVersion();

        /** Le tabelle dati (non la cronologia di Flyway) con le colonne, in ordine di caricamento. */
        List<TableInfo> tables();

        /** I valori distinti e non nulli della colonna: i nomi dei file referenziati. */
        Set<String> distinctValues(BlobColumn column);

        /** Scrive la tabella in formato testo {@code COPY} su {@code out} (che non chiude); ritorna le righe. */
        long copyOut(TableInfo table, OutputStream out);

        @Override
        void close();
    }
}
