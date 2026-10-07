package org.hexa.core.backup.port.in;

import org.hexa.core.backup.domain.ExportOptions;
import org.hexa.core.backup.domain.ExportResult;

/**
 * Esporta DB e binari in UN archivio (comando {@code export} del jar, profilo {@code backup}). Legge da una transazione REPEATABLE READ di
 * sola lettura: non scrive mai nel DB sorgente, e il server puo' restare acceso (file creati o cancellati nel frattempo possono mancare).
 */
public interface IBackupExport {

    /** @throws org.hexa.core.backup.domain.BackupException per un esito atteso (chiave mancante, file esistente...) */
    ExportResult export(ExportOptions options);
}
