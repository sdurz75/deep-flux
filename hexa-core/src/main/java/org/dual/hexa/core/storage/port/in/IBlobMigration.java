package org.dual.hexa.core.storage.port.in;

/** Migrazione una tantum dei binari verso un altro backend (oggi: locale -> WebDAV cifrato). */
public interface IBlobMigration {

    /** Esito di un giro di migrazione. */
    record Result(int migrated, int skipped, int failed, int deletedLocal, long migratedBytes) {
    }

    /** Un giro completo, idempotente e riavviabile; un file che fallisce non ferma gli altri. */
    Result migrate();
}
