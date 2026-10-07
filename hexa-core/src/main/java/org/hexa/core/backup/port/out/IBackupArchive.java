package org.hexa.core.backup.port.out;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;

import org.hexa.core.backup.domain.BackupManifest;
import org.hexa.core.backup.domain.BackupSummary;

/**
 * L'archivio del backup: uno zip scritto e letto IN SEQUENZA, cifrato per intero o in chiaro. Ordine delle voci: {@code manifest}, i binari, le tabelle
 * (in ordine di caricamento), {@code summary}. La chiave e' di 32 byte; {@code null} = niente cifratura.
 */
public interface IBackupArchive {

    /** Crea l'archivio su {@code target + ".part"}; {@link Writer#finish} lo porta sul nome definitivo. */
    Writer create(Path target, byte[] key);

    /** Apre l'archivio e legge il manifest; cifrato o no lo riconosce da solo. @throws org.hexa.core.backup.domain.BackupException se non si apre (chiave mancante o errata, non e' un backup) */
    Reader open(Path source, byte[] key);

    interface Writer extends AutoCloseable {

        void manifest(BackupManifest manifest);

        /** Inizia la voce di un binario: chi chiama scrive e CHIUDE lo stream (chiude la voce, non l'archivio). */
        OutputStream blob(String filename);

        /** Inizia la voce di una tabella (stessa regola di {@link #blob}). */
        OutputStream table(String table);

        void summary(BackupSummary summary);

        /** Chiude l'archivio e lo sposta sul nome definitivo. */
        void finish();

        /** Senza {@link #finish} butta il file temporaneo. */
        @Override
        void close();
    }

    interface Reader extends AutoCloseable {

        boolean encrypted();

        BackupManifest manifest();

        /** La voce successiva, o {@code null} alla fine. Il contenuto di quella precedente non e' piu' leggibile. */
        Entry next();

        @Override
        void close();
    }

    /** Una voce: {@code content} per i binari e le tabelle, {@code summary} per l'ultima. */
    record Entry(Kind kind, String name, InputStream content, BackupSummary summary) {

        public enum Kind { BLOB, TABLE, SUMMARY }
    }
}
