package org.dual.hexa.core.backup.adapter.in.cli;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.dual.hexa.core.backup.domain.BackupException;
import org.dual.hexa.core.backup.domain.ExportOptions;
import org.dual.hexa.core.backup.domain.ExportResult;
import org.dual.hexa.core.backup.domain.ImportOptions;
import org.dual.hexa.core.backup.domain.ImportResult;
import org.dual.hexa.core.backup.port.in.IBackupExport;
import org.dual.hexa.core.backup.port.in.IBackupImport;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * I comandi {@code export <file> [--no-encrypt]} e {@code import <file> [--replace]} del jar. {@code Application.main} attiva il profilo
 * {@code backup} e arriva qui: un {@code ApplicationRunner} gira PRIMA di {@code ApplicationReadyEvent}, e il processo esce da qui con l'esito
 * (0 = ok, 1 = errore, 2 = uso errato), quindi nessun listener "all'avvio" dell'app parte. Senza un comando (es. un test sul profilo) non fa niente.
 */
@Component
@Profile("backup")
public class BackupRunner implements ApplicationRunner {

    static final String EXPORT = "export";
    static final String IMPORT = "import";

    private static final Logger log = LoggerFactory.getLogger(BackupRunner.class);
    private static final double MB = 1024.0 * 1024.0;

    private final IBackupExport exporter;
    private final IBackupImport importer;
    private final Messages messages;
    private final ApplicationContext context;

    public BackupRunner(IBackupExport exporter, IBackupImport importer, Messages messages, ApplicationContext context) {
        this.exporter = exporter;
        this.importer = importer;
        this.messages = messages;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> words = args.getNonOptionArgs();
        if (words.isEmpty() || !Set.of(EXPORT, IMPORT).contains(words.get(0))) {
            return;
        }
        int code = execute(args, System.out, System.err);
        System.exit(SpringApplication.exit(context, () -> code));
    }

    /** Esegue il comando e ritorna il codice di uscita; separato da {@link #run} per poterlo provare senza far uscire la JVM. */
    int execute(ApplicationArguments args, PrintStream out, PrintStream err) {
        List<String> words = args.getNonOptionArgs();
        String command = words.get(0);
        Set<String> allowed = EXPORT.equals(command) ? Set.of("no-encrypt") : Set.of("replace");
        if (words.size() != 2 || !allowed.containsAll(args.getOptionNames())) {
            err.println(messages.get("backup.cli.usage"));
            return 2;
        }
        Path file = Path.of(words.get(1));
        try {
            if (EXPORT.equals(command)) {
                ExportResult result = exporter.export(new ExportOptions(file, !args.containsOption("no-encrypt")));
                out.println(messages.get("backup.cli.exported", file.toAbsolutePath(), result.tables(), result.rows(), result.blobs(),
                        result.blobBytes() / MB, messages.get(result.encrypted() ? "backup.cli.encrypted" : "backup.cli.notEncrypted")));
                warnMissing(result.missingBlobs(), err);
            } else {
                ImportResult result = importer.importFrom(new ImportOptions(file, args.containsOption("replace")));
                out.println(messages.get("backup.cli.imported", result.tables(), result.rows(), result.blobsRestored(), result.blobsSkipped()));
                warnMissing(result.missingBlobs(), err);
                if (result.undecryptableTokens() > 0) {
                    err.println(messages.get("backup.warning.tokensUndecryptable", result.undecryptableTokens()));
                }
            }
            return 0;
        } catch (BackupException e) {
            err.println(messages.get("backup.cli.failed", e.getMessage()));
            return 1;
        } catch (RuntimeException e) {
            log.error("Comando {} fallito", command, e);
            err.println(messages.get("backup.cli.failed", String.valueOf(e.getMessage())));
            return 1;
        }
    }

    private void warnMissing(List<String> missing, PrintStream err) {
        if (!missing.isEmpty()) {
            err.println(messages.get("backup.cli.missingBlobs", missing.size(), String.join(", ", missing.subList(0, Math.min(5, missing.size())))));
        }
    }
}
