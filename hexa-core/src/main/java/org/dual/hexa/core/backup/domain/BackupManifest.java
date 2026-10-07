package org.dual.hexa.core.backup.domain;

import java.time.Instant;
import java.util.List;

/**
 * Prima voce di un backup ({@code manifest.json}): quel che serve a decidere SE e COME importarlo prima di toccare qualcosa. {@code schemaVersion} e'
 * l'ultima migrazione Flyway applicata alla sorgente: l'import porta la destinazione a quella versione, carica i dati e poi migra all'ultima.
 * {@code tables} e' in ordine di caricamento (le tabelle referenziate prima di chi le referenzia).
 */
public record BackupManifest(int formatVersion, Instant createdAt, String application, String schemaVersion, String storageType,
                             List<TableInfo> tables) {

    /** Versione del formato dell'archivio: cambia solo se un backup vecchio non si puo' piu' leggere. */
    public static final int FORMAT_VERSION = 1;

    public BackupManifest {
        tables = List.copyOf(tables);
    }
}
