package org.dual.hexa.core.backup.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.NoSuchFileException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;

import org.dual.hexa.core.backup.domain.BackupException;
import org.dual.hexa.core.backup.domain.BackupManifest;
import org.dual.hexa.core.backup.domain.BackupSummary;
import org.dual.hexa.core.backup.domain.BlobColumn;
import org.dual.hexa.core.backup.domain.ExportOptions;
import org.dual.hexa.core.backup.domain.ExportResult;
import org.dual.hexa.core.backup.domain.TableInfo;
import org.dual.hexa.core.backup.port.in.IBackupExport;
import org.dual.hexa.core.backup.port.in.IBlobReferences;
import org.dual.hexa.core.backup.port.out.IBackupArchive;
import org.dual.hexa.core.backup.port.out.IDatabaseDump;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.dual.hexa.core.storage.port.in.IImageStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Export: UNO snapshot del DB, i binari che il DB referenzia (letti da {@link IImageStorageService}, quindi in chiaro anche da WebDAV: l'archivio e'
 * indipendente dal backend e dalla chiave dello storage) e poi le tabelle. L'ordine nell'archivio e' manifest, binari, tabelle, summary, cosi' l'import
 * puo' fare il commit del DB per ultimo. Un file referenziato ma assente non ferma l'export: finisce nel summary come avviso.
 */
@Service
@Profile("backup")
public class BackupExportService implements IBackupExport {

    private static final Logger log = LoggerFactory.getLogger(BackupExportService.class);

    private final IDatabaseDump dump;
    private final IBackupArchive archive;
    private final IImageStorageService storage;
    private final ObjectProvider<IBlobReferences> references;
    private final Messages messages;
    private final String application;
    private final String storageType;
    private final String encryptionKey;

    public BackupExportService(IDatabaseDump dump, IBackupArchive archive, IImageStorageService storage,
                               ObjectProvider<IBlobReferences> references, Messages messages,
                               @Value("${spring.application.name:app}") String application,
                               @Value("${storage.type:local}") String storageType,
                               @Value("${backup.encryption-key:}") String encryptionKey) {
        this.dump = dump;
        this.archive = archive;
        this.storage = storage;
        this.references = references;
        this.messages = messages;
        this.application = application;
        this.storageType = storageType;
        this.encryptionKey = encryptionKey;
    }

    @Override
    public ExportResult export(ExportOptions options) {
        byte[] key = options.encrypt() ? BackupKeys.require(encryptionKey, messages) : null;
        try (IDatabaseDump.Snapshot snapshot = dump.open()) {
            String schemaVersion = snapshot.schemaVersion();
            List<TableInfo> tables = snapshot.tables();
            Set<String> blobs = referencedBlobs(snapshot);
            log.info("Export: schema {}, {} tabelle, {} file referenziati (storage {})", schemaVersion, tables.size(), blobs.size(), storageType);

            BackupManifest manifest = new BackupManifest(BackupManifest.FORMAT_VERSION, Instant.now(), application, schemaVersion,
                    storageType, tables);
            try (IBackupArchive.Writer writer = archive.create(options.target(), key)) {
                writer.manifest(manifest);

                List<String> missing = new ArrayList<>();
                long blobCount = 0;
                long blobBytes = 0;
                for (String name : blobs) {
                    long copied = copyBlob(writer, name);
                    if (copied < 0) {
                        missing.add(name);
                        log.warn("Export: il file {} e' referenziato dal DB ma non esiste nello storage", name);
                    } else {
                        blobCount++;
                        blobBytes += copied;
                    }
                }

                Map<String, Long> rows = new LinkedHashMap<>();
                for (TableInfo table : tables) {
                    rows.put(table.name(), copyTable(writer, snapshot, table));
                }

                writer.summary(new BackupSummary(rows, blobCount, blobBytes, missing));
                writer.finish();
                long totalRows = rows.values().stream().mapToLong(Long::longValue).sum();
                return new ExportResult(tables.size(), totalRows, blobCount, blobBytes, missing, key != null);
            }
        }
    }

    private Set<String> referencedBlobs(IDatabaseDump.Snapshot snapshot) {
        Set<String> names = new TreeSet<>();
        references.orderedStream().forEach(source -> {
            for (BlobColumn column : source.blobColumns()) {
                names.addAll(snapshot.distinctValues(column));
            }
        });
        return names;
    }

    /** I byte copiati, o -1 se il file non esiste (un nome che non e' un filename valido vale come assente). */
    private long copyBlob(IBackupArchive.Writer writer, String name) {
        InputStream in;
        try {
            OptionalLong size = storage.size(name);
            if (size.isEmpty()) {
                return -1;
            }
            in = storage.openRange(name, 0, size.getAsLong());
        } catch (IllegalArgumentException | NoSuchFileException e) {
            return -1;
        } catch (IOException e) {
            throw new BackupException(messages.get("backup.error.blobRead", name, e.getMessage()), e);
        }
        try (in; OutputStream out = writer.blob(name)) {
            return in.transferTo(out);
        } catch (IOException e) {
            throw new BackupException(messages.get("backup.error.blobRead", name, e.getMessage()), e);
        }
    }

    private long copyTable(IBackupArchive.Writer writer, IDatabaseDump.Snapshot snapshot, TableInfo table) {
        try (OutputStream out = writer.table(table.name())) {
            return snapshot.copyOut(table, out);
        } catch (IOException e) {
            throw new BackupException(messages.get("backup.error.io", e.getMessage()), e);
        }
    }
}
