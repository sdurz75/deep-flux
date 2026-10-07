package org.dual.hexa.core.backup.port.in;

import org.dual.hexa.core.backup.domain.ImportOptions;
import org.dual.hexa.core.backup.domain.ImportResult;

/**
 * Ripristina un archivio nel DB e nello storage della configurazione corrente (comando {@code import} del jar). I binari prima (riavviabile: i
 * file gia' presenti si saltano), il DB per ultimo e in una sola transazione. Il server deve essere fermo.
 */
public interface IBackupImport {

    /** @throws org.dual.hexa.core.backup.domain.BackupException per un esito atteso (DB non vergine, chiave errata, archivio corrotto...) */
    ImportResult importFrom(ImportOptions options);
}
