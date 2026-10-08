package org.dual.hexa.core.backup.application;

import java.util.LinkedHashMap;
import java.util.Map;

import org.dual.hexa.core.backup.domain.BackupException;
import org.dual.hexa.core.backup.domain.BackupManifest;
import org.dual.hexa.core.backup.domain.BackupSummary;
import org.dual.hexa.core.backup.domain.ImportOptions;
import org.dual.hexa.core.backup.domain.ImportResult;
import org.dual.hexa.core.backup.domain.TableInfo;
import org.dual.hexa.core.backup.port.in.IBackupImport;
import org.dual.hexa.core.backup.port.out.IBackupArchive;
import org.dual.hexa.core.backup.port.out.IDatabaseRestore;
import org.dual.hexa.core.events.domain.CoreEventSource;
import org.dual.hexa.core.events.port.in.ISystemEvents;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.dual.hexa.core.storage.port.in.IImageStorageService;
import org.dual.hexa.core.secrets.port.in.ISecrets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Import. Prima i controlli, tutti in sola lettura (formato, versione dello schema, database vergine o {@code --replace}); poi i binari, scritti
 * nello storage della configurazione corrente col loro nome (un file gia' presente si salta: i nomi sono casuali e le scritture atomiche, quindi
 * presente = completo, e un import interrotto si rilancia); per ULTIMO il DB, in una sola transazione: schema portato alla versione del backup,
 * tabelle svuotate (le migrazioni seminano righe), dati caricati, sequenze riallineate, commit; infine le migrazioni mancanti, cosi' un backup
 * piu' vecchio entra in un jar piu' nuovo. Un errore sui binari lascia il DB intatto; uno sul DB lo lascia migrato ma vuoto ({@code --replace} per riprovare).
 */
@Service
@Profile("backup")
public class BackupImportService implements IBackupImport {

    private static final Logger log = LoggerFactory.getLogger(BackupImportService.class);

    private final IBackupArchive archive;
    private final IDatabaseRestore restore;
    private final IImageStorageService storage;
    private final ISecrets secrets;
    private final ISystemEvents events;
    private final Messages messages;
    private final String encryptionKey;

    public BackupImportService(IBackupArchive archive, IDatabaseRestore restore, IImageStorageService storage, ISecrets secrets,
                               ISystemEvents events, Messages messages, @Value("${backup.encryption-key:}") String encryptionKey) {
        this.archive = archive;
        this.restore = restore;
        this.storage = storage;
        this.secrets = secrets;
        this.events = events;
        this.messages = messages;
        this.encryptionKey = encryptionKey;
    }

    @Override
    public ImportResult importFrom(ImportOptions options) {
        try (IBackupArchive.Reader reader = archive.open(options.source(), BackupKeys.lenient(encryptionKey))) {
            BackupManifest manifest = reader.manifest();
            preflight(manifest, options);
            log.info("Import: backup del {} (schema {}, storage di origine {}, {})", manifest.createdAt(), manifest.schemaVersion(),
                    manifest.storageType(), reader.encrypted() ? "cifrato" : "NON cifrato");
            return restore(reader, manifest, options);
        }
    }

    private void preflight(BackupManifest manifest, ImportOptions options) {
        if (manifest.formatVersion() > BackupManifest.FORMAT_VERSION) {
            throw new BackupException(messages.get("backup.error.formatUnsupported", manifest.formatVersion()));
        }
        if (manifest.schemaVersion() == null || !restore.knowsSchemaVersion(manifest.schemaVersion())) {
            throw new BackupException(messages.get("backup.error.schemaTooNew", manifest.schemaVersion()));
        }
        if (!restore.isVirgin() && !options.replace()) {
            throw new BackupException(messages.get("backup.error.dbNotVirgin"));
        }
    }

    private ImportResult restore(IBackupArchive.Reader reader, BackupManifest manifest, ImportOptions options) {
        long restored = 0;
        long skipped = 0;
        Map<String, Long> loaded = new LinkedHashMap<>();
        BackupSummary summary = null;
        IDatabaseRestore.Load load = null;
        boolean databaseTouched = false;
        try {
            for (IBackupArchive.Entry entry = reader.next(); entry != null; entry = reader.next()) {
                switch (entry.kind()) {
                    case BLOB -> {
                        if (restoreBlob(entry)) {
                            restored++;
                        } else {
                            skipped++;
                        }
                    }
                    case TABLE -> {
                        if (load == null) {
                            databaseTouched = true;
                            load = prepareDatabase(manifest, options);
                        }
                        loaded.put(entry.name(), load.copyIn(tableOf(manifest, entry.name()), entry.content()));
                    }
                    case SUMMARY -> summary = entry.summary();
                }
            }
            if (summary == null) {
                throw new BackupException(messages.get("backup.error.truncated", "summary"));
            }
            if (load == null) {
                databaseTouched = true;
                load = prepareDatabase(manifest, options);
            }
            verify(summary, loaded, restored + skipped);
            load.resetSequences();
            load.commit();
        } catch (RuntimeException e) {
            if (databaseTouched) {
                throw new BackupException(messages.get("backup.error.dbLoadFailed", String.valueOf(e.getMessage())), e);
            }
            throw e;
        } finally {
            if (load != null) {
                load.close();
            }
        }
        restore.migrateToLatest();
        int undecryptable = checkSecrets();
        long rows = loaded.values().stream().mapToLong(Long::longValue).sum();
        return new ImportResult(loaded.size(), rows, restored, skipped, summary.missingBlobs(), undecryptable);
    }

    /** {@code true} se l'ha scritto, {@code false} se c'era gia'. */
    private boolean restoreBlob(IBackupArchive.Entry entry) {
        try {
            if (storage.size(entry.name()).isPresent()) {
                return false;
            }
            storage.restore(entry.name(), entry.content());
            return true;
        } catch (IllegalArgumentException e) {
            throw new BackupException(messages.get("backup.error.entryInvalid", "blobs/" + entry.name()), e);
        }
    }

    private IDatabaseRestore.Load prepareDatabase(BackupManifest manifest, ImportOptions options) {
        if (options.replace() && !restore.isVirgin()) {
            log.warn("Import: --replace, azzero lo schema esistente");
            restore.wipe();
        }
        restore.migrateTo(manifest.schemaVersion());
        IDatabaseRestore.Load load = restore.beginLoad();
        try {
            load.truncateAll();
        } catch (RuntimeException e) {
            load.close();
            throw e;
        }
        return load;
    }

    private TableInfo tableOf(BackupManifest manifest, String name) {
        return manifest.tables().stream().filter(t -> t.name().equals(name)).findFirst()
                .orElseThrow(() -> new BackupException(messages.get("backup.error.entryInvalid", "db/" + name + ".copy")));
    }

    private void verify(BackupSummary summary, Map<String, Long> loaded, long blobs) {
        for (Map.Entry<String, Long> expected : summary.rows().entrySet()) {
            long actual = loaded.getOrDefault(expected.getKey(), -1L);
            if (actual != expected.getValue()) {
                throw new BackupException(messages.get("backup.error.rowsMismatch", expected.getKey(), expected.getValue(), actual));
            }
        }
        if (blobs != summary.blobCount()) {
            throw new BackupException(messages.get("backup.error.blobsMismatch", summary.blobCount(), blobs));
        }
    }

    /** I token si copiano cifrati con la chiave dello storage di ORIGINE: se la attuale e' un'altra non si aprono, e vanno reinseriti. Mai bloccante. */
    private int checkSecrets() {
        try {
            int broken = secrets.undecryptableCount();
            if (broken > 0) {
                events.warn(CoreEventSource.SECRETS, "restoreSecrets", null, messages.get("backup.warning.secretsUndecryptable", broken));
            }
            return broken;
        } catch (RuntimeException e) {
            log.warn("Import: verifica dei token non riuscita", e);
            return 0;
        }
    }
}
