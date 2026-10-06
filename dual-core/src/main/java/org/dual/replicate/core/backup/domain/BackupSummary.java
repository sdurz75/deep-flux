package org.dual.replicate.core.backup.domain;

import java.util.List;
import java.util.Map;

/**
 * Ultima voce di un backup ({@code summary.json}): cio' che l'export ha davvero scritto, per verificare l'import. {@code missingBlobs} sono i file
 * referenziati dal DB ma assenti dallo storage al momento dell'export (avviso, non errore).
 */
public record BackupSummary(Map<String, Long> rows, long blobCount, long blobBytes, List<String> missingBlobs) {

    public BackupSummary {
        rows = Map.copyOf(rows);
        missingBlobs = List.copyOf(missingBlobs);
    }
}
