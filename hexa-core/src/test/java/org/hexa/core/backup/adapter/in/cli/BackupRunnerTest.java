package org.hexa.core.backup.adapter.in.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

import org.hexa.core.backup.domain.BackupException;
import org.hexa.core.backup.domain.ExportOptions;
import org.hexa.core.backup.domain.ExportResult;
import org.hexa.core.backup.domain.ImportOptions;
import org.hexa.core.backup.domain.ImportResult;
import org.hexa.core.backup.port.in.IBackupExport;
import org.hexa.core.backup.port.in.IBackupImport;
import org.hexa.core.kernel.i18n.Messages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ResourceBundleMessageSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** I codici di uscita e gli argomenti dei comandi, senza far uscire la JVM e senza database. */
class BackupRunnerTest {

    private IBackupExport exporter;
    private IBackupImport importer;
    private BackupRunner runner;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() {
        exporter = mock(IBackupExport.class);
        importer = mock(IBackupImport.class);
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasenames("messages-core");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        runner = new BackupRunner(exporter, importer, new Messages(source), mock(ApplicationContext.class));
    }

    private int run(String... args) {
        return runner.execute(new DefaultApplicationArguments(args), new PrintStream(out), new PrintStream(err));
    }

    @Test
    void exportEncryptsUnlessToldOtherwise() {
        when(exporter.export(any())).thenReturn(new ExportResult(3, 10, 2, 1024, List.of(), true));

        assertThat(run("export", "backup.dfb")).isZero();
        verify(exporter).export(new ExportOptions(Path.of("backup.dfb"), true));
        assertThat(out.toString()).contains("backup.dfb");

        assertThat(run("export", "plain.zip", "--no-encrypt")).isZero();
        verify(exporter).export(new ExportOptions(Path.of("plain.zip"), false));
    }

    @Test
    void importReplacesOnlyWhenAsked() {
        when(importer.importFrom(any())).thenReturn(new ImportResult(3, 10, 2, 0, List.of(), 0));

        assertThat(run("import", "backup.dfb")).isZero();
        verify(importer).importFrom(new ImportOptions(Path.of("backup.dfb"), false));

        assertThat(run("import", "backup.dfb", "--replace")).isZero();
        verify(importer).importFrom(new ImportOptions(Path.of("backup.dfb"), true));
    }

    @Test
    void badArgumentsAreAUsageErrorThatTouchesNothing() {
        assertThat(run("export")).isEqualTo(2);
        assertThat(run("export", "a", "b")).isEqualTo(2);
        assertThat(run("export", "a", "--replace")).as("--replace non esiste per l'export").isEqualTo(2);
        assertThat(run("import", "a", "--no-encrypt")).as("--no-encrypt non esiste per l'import").isEqualTo(2);
        assertThat(run("import", "a", "--force")).isEqualTo(2);

        assertThat(err.toString()).contains("java -jar app.jar");
        verifyNoInteractions(exporter, importer);
    }

    @Test
    void anExpectedFailureIsPrintedAndExitsWithOne() {
        when(importer.importFrom(any())).thenThrow(new BackupException("database non vergine"));

        assertThat(run("import", "backup.dfb")).isEqualTo(1);
        assertThat(err.toString()).contains("database non vergine");
    }

    @Test
    void anUnexpectedFailureExitsWithOneToo() {
        when(exporter.export(any())).thenThrow(new IllegalStateException("boom"));

        assertThat(run("export", "x.dfb")).isEqualTo(1);
        assertThat(err.toString()).contains("boom");
    }

    @Test
    void missingFilesAndUndecryptableTokensAreWarnings() {
        when(importer.importFrom(any())).thenReturn(new ImportResult(3, 10, 2, 0, List.of("gone.png"), 2));

        assertThat(run("import", "backup.dfb")).isZero();
        assertThat(err.toString()).contains("gone.png").contains("/tokens");
    }
}
