package org.dual.replicate.core.storage.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.dual.replicate.core.events.domain.CoreEventSource;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.dual.replicate.core.storage.port.out.IBlobImportTarget;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Migrazione una tantum dei binari da {@code storage.images-dir} (filesystem locale) a WebDAV cifrato, opt-in con
 * {@code storage.migration.from-local.enabled=true} e {@code storage.type=webdav}. Parte all'avvio dell'app
 * (ApplicationReadyEvent, come GenerationRecoveryService) e si spegne da sola a fine giro: poi va rimessa a false.
 *
 * <p>Idempotente e riavviabile: un file gia' presente sul server (HEAD) viene saltato, quindi dopo un'interruzione o
 * un errore basta rilanciarla. I file locali NON si toccano, salvo {@code delete-local=true}: in quel caso ciascuno si
 * elimina solo dopo aver verificato che la dimensione in chiaro riportata dal server coincide. Finche' non ha finito,
 * i file non ancora migrati non sono serviti (con {@code type=webdav} si legge solo da WebDAV).
 */
@Component
@ConditionalOnProperty(name = "storage.migration.from-local.enabled", havingValue = "true")
public class LocalToWebDavMigrator {

    private static final Logger log = LoggerFactory.getLogger(LocalToWebDavMigrator.class);

    /** Esito di un giro di migrazione. */
    public record Result(int migrated, int skipped, int failed, int deletedLocal, long migratedBytes) {
    }

    private final IBlobImportTarget target;
    private final ISystemEvents systemEvents;
    private final Path sourceDir;
    private final boolean deleteLocal;

    public LocalToWebDavMigrator(ObjectProvider<IBlobImportTarget> target, ISystemEvents systemEvents,
                                 @Value("${storage.images-dir}") String imagesDir,
                                 @Value("${storage.migration.from-local.delete-local:false}") boolean deleteLocal) {
        this.target = target.getIfAvailable();
        if (this.target == null) {
            throw new IllegalStateException(
                    "storage.migration.from-local.enabled=true richiede storage.type=webdav (backend di destinazione)");
        }
        this.systemEvents = systemEvents;
        this.sourceDir = Path.of(imagesDir);
        this.deleteLocal = deleteLocal;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        migrate();
    }

    /** Un giro completo; un file che fallisce non ferma gli altri (registrato con {@link ISystemEvents}). */
    public Result migrate() {
        List<Path> files = localFiles();
        log.info("Migrazione storage locale -> WebDAV: {} file in {} (delete-local={})", files.size(), sourceDir,
                deleteLocal);
        int migrated = 0;
        int skipped = 0;
        int failed = 0;
        int deleted = 0;
        long bytes = 0;
        for (Path file : files) {
            String name = file.getFileName().toString();
            try {
                long size = Files.size(file);
                if (target.existsRemotely(name)) {
                    skipped++;
                } else {
                    target.importFile(name, file);
                    migrated++;
                    bytes += size;
                }
                if (deleteLocal && verifiedOnServer(name, size)) {
                    Files.delete(file);
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

    /** File regolari con un nome valido per lo storage (niente temporanei {@code .part}), in ordine di nome. */
    private List<Path> localFiles() {
        if (!Files.isDirectory(sourceDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(sourceDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(f -> !f.getFileName().toString().endsWith(".part"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Impossibile elencare " + sourceDir, e);
        }
    }
}
