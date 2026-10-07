package org.hexa.core.storage.application;

import java.io.IOException;
import java.util.List;

import org.hexa.core.events.domain.CoreEventSource;
import org.hexa.core.events.port.in.ISystemEvents;
import org.hexa.core.storage.domain.ImportableFile;
import org.hexa.core.storage.port.in.IBlobMigration;
import org.hexa.core.storage.port.out.IBlobImportSource;
import org.hexa.core.storage.port.out.IBlobImportTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Migrazione una tantum dei binari da {@code storage.images-dir} (filesystem locale) a WebDAV cifrato, opt-in con
 * {@code storage.migration.from-local.enabled=true} e {@code storage.type=webdav}. Parte all'avvio dell'app
 * ({@code LocalToWebDavMigrationStarter}, adapter in) e si spegne da sola a fine giro: poi va rimessa a false. Origine e destinazione
 * sono porte ({@link IBlobImportSource}, {@link IBlobImportTarget}): qui nessun accesso al filesystem.
 *
 * <p>Idempotente e riavviabile: un file gia' presente sul server (HEAD) viene saltato, quindi dopo un'interruzione o
 * un errore basta rilanciarla. I file locali NON si toccano, salvo {@code delete-local=true}: in quel caso ciascuno si
 * elimina solo dopo aver verificato che la dimensione in chiaro riportata dal server coincide. Finche' non ha finito,
 * i file non ancora migrati non sono serviti (con {@code type=webdav} si legge solo da WebDAV).
 */
@Component
@ConditionalOnProperty(name = "storage.migration.from-local.enabled", havingValue = "true")
public class LocalToWebDavMigrator implements IBlobMigration {

    private static final Logger log = LoggerFactory.getLogger(LocalToWebDavMigrator.class);

    private final IBlobImportSource source;
    private final IBlobImportTarget target;
    private final ISystemEvents systemEvents;
    private final boolean deleteLocal;

    public LocalToWebDavMigrator(IBlobImportSource source, ObjectProvider<IBlobImportTarget> target, ISystemEvents systemEvents,
                                 @Value("${storage.migration.from-local.delete-local:false}") boolean deleteLocal) {
        this.source = source;
        this.target = target.getIfAvailable();
        if (this.target == null) {
            throw new IllegalStateException(
                    "storage.migration.from-local.enabled=true richiede storage.type=webdav (backend di destinazione)");
        }
        this.systemEvents = systemEvents;
        this.deleteLocal = deleteLocal;
    }

    /** Un giro completo; un file che fallisce non ferma gli altri (registrato con {@link ISystemEvents}). */
    @Override
    public Result migrate() {
        List<ImportableFile> files = localFiles();
        log.info("Migrazione storage locale -> WebDAV: {} file in {} (delete-local={})", files.size(), source,
                deleteLocal);
        int migrated = 0;
        int skipped = 0;
        int failed = 0;
        int deleted = 0;
        long bytes = 0;
        for (ImportableFile file : files) {
            String name = file.filename();
            try {
                long size = file.size();
                if (target.existsRemotely(name)) {
                    skipped++;
                } else {
                    target.importFile(name, file.path());
                    migrated++;
                    bytes += size;
                }
                if (deleteLocal && verifiedOnServer(name, size)) {
                    source.delete(file);
                    deleted++;
                }
            } catch (IOException | RuntimeException e) {
                failed++;
                systemEvents.record(CoreEventSource.STORAGE, "migrateLocalToWebDav", e);
            }
        }
        Result result = new Result(migrated, skipped, failed, deleted, bytes);
        log.info("Migrazione storage locale -> WebDAV conclusa: {} (imposta storage.migration.from-local.enabled=false)",
                result);
        return result;
    }

    /** La dimensione in chiaro sul server deve coincidere con quella locale prima di eliminare l'originale. */
    private boolean verifiedOnServer(String name, long localSize) throws IOException {
        long remoteSize = target.remotePlainSize(name);
        if (remoteSize != localSize) {
            throw new IOException("Dimensione sul server (" + remoteSize + ") diversa da quella locale (" + localSize
                    + ") per " + name + ": file locale conservato");
        }
        return true;
    }

    private List<ImportableFile> localFiles() {
        try {
            return source.list();
        } catch (IOException e) {
            throw new IllegalStateException("Impossibile elencare " + source, e);
        }
    }
}
