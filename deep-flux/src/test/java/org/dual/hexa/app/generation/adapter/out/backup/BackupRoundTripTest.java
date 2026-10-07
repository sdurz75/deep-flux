package org.dual.hexa.app.generation.adapter.out.backup;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import javax.sql.DataSource;

import org.dual.hexa.core.backup.adapter.out.archive.ZipBackupArchive;
import org.dual.hexa.app.training.adapter.out.backup.TrainingBlobReferences;
import org.dual.hexa.core.backup.adapter.out.jdbc.JdbcDatabaseDump;
import org.dual.hexa.core.backup.adapter.out.jdbc.JdbcDatabaseRestore;
import org.dual.hexa.core.backup.application.BackupExportService;
import org.dual.hexa.core.backup.application.BackupImportService;
import org.dual.hexa.core.backup.domain.BackupException;
import org.dual.hexa.core.backup.domain.BlobColumn;
import org.dual.hexa.core.backup.domain.ExportOptions;
import org.dual.hexa.core.backup.domain.ExportResult;
import org.dual.hexa.core.backup.domain.ImportOptions;
import org.dual.hexa.core.backup.domain.ImportResult;
import org.dual.hexa.core.backup.domain.TableInfo;
import org.dual.hexa.core.backup.port.in.IBlobReferences;
import org.dual.hexa.core.backup.port.out.IDatabaseDump;
import org.dual.hexa.core.backup.port.out.IDatabaseRestore;
import org.dual.hexa.core.events.domain.CoreEventSource;
import org.dual.hexa.core.events.port.in.ISystemEvents;
import org.dual.hexa.core.kernel.crypto.ChunkedAesGcmCipher;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.dual.hexa.core.storage.adapter.out.local.LocalFsBlobBackend;
import org.dual.hexa.core.storage.adapter.out.webdav.FakeWebDavServer;
import org.dual.hexa.core.storage.adapter.out.webdav.WebDavBlobBackend;
import org.dual.hexa.core.storage.application.ImageStorageService;
import org.dual.hexa.core.storage.port.in.IImageStorageService;
import org.dual.hexa.core.storage.port.out.IRemoteFileFetcher;
import org.dual.hexa.core.tokens.port.in.IApiTokens;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Export e import veri (adapter jdbc + zip, servizi, storage locale) fra DUE database dedicati dello stesso container dei test, con lo schema
 * reale core+app: mai il database condiviso dagli altri test. Il contenuto si confronta tabella per tabella (conteggio + digest di ogni riga,
 * embedding, bytea e testi con caratteri speciali compresi) e i binari byte per byte.
 */
@SpringBootTest
class BackupRoundTripTest {

    private static final String[] LOCATIONS = {"classpath:db/migration/core", "classpath:db/migration/ai", "classpath:db/migration/app"};
    private static final String OLDER_SCHEMA = "2026.10.04.1000";

    @Value("${spring.datasource.url}")
    private String url;
    @Value("${spring.datasource.username}")
    private String username;
    @Value("${spring.datasource.password}")
    private String password;

    @TempDir
    Path tmp;

    private final byte[] keyBytes = bytes(32, 11);
    private final String key = Base64.getEncoder().encodeToString(keyBytes);
    private final Messages messages = realMessages();
    private final Map<String, byte[]> blobs = new LinkedHashMap<>();

    private DataSource src;
    private DataSource dst;
    private IImageStorageService srcStorage;
    private IImageStorageService dstStorage;
    private Path dstDir;

    @BeforeEach
    void setUp() throws Exception {
        src = freshDatabase("bk_src");
        dst = freshDatabase("bk_dst");
        srcStorage = storage(tmp.resolve("src-images"));
        dstDir = tmp.resolve("dst-images");
        dstStorage = storage(dstDir);
    }

    // --- percorso felice ----------------------------------------------------------------------------------------------------

    @Test
    void anEncryptedBackupRestoresDatabaseBlobsAndSequences() throws Exception {
        migrate(src, null);
        populate();
        Path file = tmp.resolve("backup.dfb");

        ExportResult exported = exporter(src, key).export(new ExportOptions(file, true));

        assertThat(exported.encrypted()).isTrue();
        assertThat(exported.blobs()).isEqualTo(blobs.size());
        assertThat(exported.missingBlobs()).isEmpty();
        assertThat(Files.readAllBytes(file)).startsWith('D', 'F', 'X', '1');
        assertThat(new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.ISO_8859_1))
                .as("l'archivio cifrato non lascia leggere nulla").doesNotContain("prompt con").doesNotContain("manifest.json").doesNotContain("generation");

        ImportResult imported = importer(dst, key, 0).importFrom(new ImportOptions(file, false));

        assertThat(imported.blobsRestored()).isEqualTo(blobs.size());
        assertThat(imported.rows()).isPositive();
        assertThat(digests(dst)).isEqualTo(digests(src));
        assertBlobsRestored();
        assertSequencesAreAheadOfTheData(dst);
        // Il primo insert dopo il ripristino non collide con gli id caricati.
        new JdbcTemplate(dst).update("INSERT INTO generation (external_id, model, prompt, status, created_at) VALUES ('new', 'm', 'p', 'PENDING', now())");
        assertThat(latestVersion(dst)).isEqualTo(latestKnownVersion());
    }

    @Test
    void aPlainBackupIsReadableWithoutAKey() throws Exception {
        migrate(src, null);
        populate();
        Path file = tmp.resolve("plain.zip");

        ExportResult exported = exporter(src, "").export(new ExportOptions(file, false));
        ImportResult imported = importer(dst, "", 0).importFrom(new ImportOptions(file, false));

        assertThat(exported.encrypted()).isFalse();
        assertThat(Files.readAllBytes(file)).startsWith('P', 'K');
        assertThat(imported.blobsRestored()).isEqualTo(blobs.size());
        assertThat(digests(dst)).isEqualTo(digests(src));
    }

    @Test
    void aBackupOfAnOlderSchemaIsMigratedUpToTheLatestAfterLoading() throws Exception {
        migrate(src, OLDER_SCHEMA);
        JdbcTemplate sj = new JdbcTemplate(src);
        sj.update("INSERT INTO generation (external_id, model, prompt, status, created_at) VALUES ('ext', 'm', 'vecchio', 'SUCCEEDED', now())");
        long id = sj.queryForObject("SELECT id FROM generation", Long.class);
        sj.update("INSERT INTO generation_image (generation_id, ordinal, filename) VALUES (?, 0, 'old.png')", id);
        put("old.png", bytes(5000, 1));
        Path file = tmp.resolve("old.dfb");

        exporter(src, key).export(new ExportOptions(file, true));
        importer(dst, key, 0).importFrom(new ImportOptions(file, false));

        JdbcTemplate dj = new JdbcTemplate(dst);
        assertThat(dj.queryForObject("SELECT prompt FROM generation", String.class)).isEqualTo("vecchio");
        assertThat(dj.queryForObject("SELECT to_regclass('generation_tag') IS NOT NULL", Boolean.class)).as("migrazioni successive applicate").isTrue();
        assertThat(dj.queryForObject("SELECT count(*) FROM generation_tag", Long.class)).isZero();
        assertThat(latestVersion(dst)).isEqualTo(latestKnownVersion());
        assertBlobsRestored();
    }

    /**
     * Un DB piu' vecchio del jar che esporta non ha ancora le tabelle di una feature nuova (il profilo backup non migra): la colonna dichiarata non
     * puo' referenziare nulla. Se si interrogasse comunque, PostgreSQL aborterebbe la transazione dello snapshot e con lei tutto l'export.
     */
    @Test
    void aDeclaredColumnMissingFromTheSourceSchemaHasNoReferencesAndDoesNotBreakTheSnapshot() throws Exception {
        migrate(src, null);

        try (IDatabaseDump.Snapshot snapshot = new JdbcDatabaseDump(src, messages).open()) {
            assertThat(snapshot.distinctValues(new BlobColumn("table_that_is_not_there", "filename"))).isEmpty();
            assertThat(snapshot.distinctValues(new BlobColumn("generation", "column_that_is_not_there"))).isEmpty();
            assertThat(snapshot.schemaVersion()).as("lo snapshot e' ancora utilizzabile: la transazione non e' stata abortita").isNotBlank();
        }
    }

    @Test
    void aReferencedFileMissingFromTheStorageIsAWarningNotAnError() throws Exception {
        migrate(src, null);
        populate();
        new JdbcTemplate(src).update("INSERT INTO generation_image (generation_id, ordinal, filename) SELECT min(id), 9, 'gone.png' FROM generation");
        Path file = tmp.resolve("missing.dfb");

        ExportResult exported = exporter(src, key).export(new ExportOptions(file, true));
        ImportResult imported = importer(dst, key, 0).importFrom(new ImportOptions(file, false));

        assertThat(exported.missingBlobs()).containsExactly("gone.png");
        assertThat(imported.missingBlobs()).containsExactly("gone.png");
        assertThat(digests(dst)).isEqualTo(digests(src));
    }

    // --- WebDAV (cache disattivata, come nel profilo backup) ------------------------------------------------------------------

    /** Export da uno storage WebDAV cifrato: i blob escono in chiaro (decifrati con la chiave dello storage) e l'import li scrive in locale. */
    @Test
    void anExportReadsEncryptedBlobsFromWebDavAndAnImportCanWriteThemToALocalStorage() throws Exception {
        FakeWebDavServer dav = new FakeWebDavServer();
        try {
            ISystemEvents events = mock(ISystemEvents.class);
            srcStorage = webDavStorage(dav, bytes(32, 21), events, "cache-src");
            migrate(src, null);
            populate();
            assertThat(dav.store).as("sul server ci sono solo blob cifrati").hasSize(blobs.size());
            Path file = tmp.resolve("from-webdav.dfb");

            ExportResult exported = exporter(src, key).export(new ExportOptions(file, true));
            importer(dst, key, 0).importFrom(new ImportOptions(file, false));

            assertThat(exported.blobs()).isEqualTo(blobs.size());
            assertThat(exported.missingBlobs()).isEmpty();
            assertBlobsRestored();
            assertThat(digests(dst)).isEqualTo(digests(src));
            verifyNoInteractions(events); // nessun errore di cache registrato: ISystemEvents#record non e' mai stato chiamato
        } finally {
            dav.stop();
        }
    }

    /** Import verso WebDAV con una chiave dello storage DIVERSA da quella della sorgente: l'archivio e' indipendente dal backend. */
    @Test
    void anImportCanWriteToWebDavEncryptingWithTheTargetsOwnKey() throws Exception {
        FakeWebDavServer dav = new FakeWebDavServer();
        try {
            ISystemEvents events = mock(ISystemEvents.class);
            migrate(src, null);
            populate();
            byte[] visible = "VISIBLE-IN-CLEAR-VISIBLE-IN-CLEAR-VISIBLE".getBytes();
            put("marker.png", visible);
            new JdbcTemplate(src).update("INSERT INTO generation_image (generation_id, ordinal, filename) SELECT min(id), 8, 'marker.png' FROM generation");
            Path file = tmp.resolve("to-webdav.dfb");
            exporter(src, key).export(new ExportOptions(file, true));
            dstStorage = webDavStorage(dav, bytes(32, 77), events, "cache-dst");

            ImportResult imported = importer(dst, key, 0).importFrom(new ImportOptions(file, false));

            assertThat(imported.blobsRestored()).isEqualTo(blobs.size());
            assertBlobsRestored();
            assertThat(dav.store).hasSize(blobs.size());
            for (byte[] stored : dav.store.values()) {
                assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("VISIBLE-IN-CLEAR");
            }
            assertThat(digests(dst)).isEqualTo(digests(src));
            verifyNoInteractions(events);
        } finally {
            dav.stop();
        }
    }

    // --- credenziali e chiavi ----------------------------------------------------------------------------------------------

    @Test
    void exportingWithoutAKeyIsRefusedUnlessPlainIsExplicit() throws Exception {
        migrate(src, null);
        Path file = tmp.resolve("nokey.dfb");

        assertThatThrownBy(() -> exporter(src, "").export(new ExportOptions(file, true)))
                .isInstanceOf(BackupException.class).hasMessageContaining("BACKUP_ENCRYPTION_KEY");
        assertThatThrownBy(() -> exporter(src, "not-base64-!!").export(new ExportOptions(file, true)))
                .isInstanceOf(BackupException.class);
        assertThat(file).doesNotExist();
        assertThat(tmp.resolve("nokey.dfb.part")).doesNotExist();
    }

    @Test
    void aWrongKeyFailsBeforeAnythingIsWritten() throws Exception {
        migrate(src, null);
        populate();
        Path file = tmp.resolve("backup.dfb");
        exporter(src, key).export(new ExportOptions(file, true));
        String otherKey = Base64.getEncoder().encodeToString(bytes(32, 99));

        assertThatThrownBy(() -> importer(dst, otherKey, 0).importFrom(new ImportOptions(file, false))).isInstanceOf(BackupException.class);
        assertThatThrownBy(() -> importer(dst, "", 0).importFrom(new ImportOptions(file, false)))
                .isInstanceOf(BackupException.class).hasMessageContaining("BACKUP_ENCRYPTION_KEY");

        assertThat(new JdbcDatabaseRestore(dst, LOCATIONS, messages).isVirgin()).isTrue();
        assertThat(dstDir).as("nessun file scritto").satisfiesAnyOf(d -> assertThat(d).doesNotExist(),
                d -> assertThat(files(d)).isEmpty());
    }

    @Test
    void tokensThatTheCurrentKeyCannotOpenAreReportedButDoNotBlockTheRestore() throws Exception {
        migrate(src, null);
        populate();
        Path file = tmp.resolve("backup.dfb");
        exporter(src, key).export(new ExportOptions(file, true));
        ISystemEvents events = mock(ISystemEvents.class);

        ImportResult imported = importer(dst, key, 2, events).importFrom(new ImportOptions(file, false));

        assertThat(imported.undecryptableTokens()).isEqualTo(2);
        verify(events).warn(eq(CoreEventSource.TOKENS), eq("restoreTokens"), eq(null), anyString());
        assertThat(digests(dst)).isEqualTo(digests(src));
    }

    // --- sicurezza dell'import ----------------------------------------------------------------------------------------------

    @Test
    void aDatabaseThatAlreadyHasTheSchemaIsRefusedUnlessReplaceIsGiven() throws Exception {
        migrate(src, null);
        populate();
        Path file = tmp.resolve("backup.dfb");
        exporter(src, key).export(new ExportOptions(file, true));
        importer(dst, key, 0).importFrom(new ImportOptions(file, false));
        new JdbcTemplate(dst).update("UPDATE generation SET prompt = 'modificato dopo il primo import'");

        assertThatThrownBy(() -> importer(dst, key, 0).importFrom(new ImportOptions(file, false)))
                .isInstanceOf(BackupException.class).hasMessageContaining("--replace");
        assertThat(new JdbcTemplate(dst).queryForObject("SELECT count(*) FROM generation WHERE prompt = 'modificato dopo il primo import'",
                Long.class)).as("rifiutato senza toccare nulla").isPositive();

        ImportResult replaced = importer(dst, key, 0).importFrom(new ImportOptions(file, true));

        assertThat(replaced.blobsSkipped()).as("i file gia' presenti si saltano").isEqualTo(blobs.size());
        assertThat(digests(dst)).isEqualTo(digests(src));
    }

    @Test
    void aFailureWhileLoadingTheDatabaseRollsEverythingBack() throws Exception {
        migrate(src, null);
        populate();
        Path file = tmp.resolve("backup.dfb");
        exporter(src, key).export(new ExportOptions(file, true));
        JdbcDatabaseRestore real = new JdbcDatabaseRestore(dst, LOCATIONS, messages);
        IDatabaseRestore failingAtTheEnd = new FailingAtCommit(real);

        BackupImportService service = new BackupImportService(new ZipBackupArchive(messages), failingAtTheEnd, dstStorage, tokens(0),
                mock(ISystemEvents.class), messages, key);

        assertThatThrownBy(() -> service.importFrom(new ImportOptions(file, false)))
                .isInstanceOf(BackupException.class).hasMessageContaining("--replace");
        JdbcTemplate dj = new JdbcTemplate(dst);
        assertThat(dj.queryForObject("SELECT count(*) FROM generation", Long.class)).isZero();
        assertThat(dj.queryForObject("SELECT count(*) FROM chat_message", Long.class)).isZero();

        // E si riparte con --replace.
        importer(dst, key, 0).importFrom(new ImportOptions(file, true));
        assertThat(digests(dst)).isEqualTo(digests(src));
    }

    @Test
    void aTruncatedBackupFailsAndLeavesNoData() throws Exception {
        migrate(src, null);
        populate();
        Path file = tmp.resolve("backup.dfb");
        exporter(src, key).export(new ExportOptions(file, true));
        byte[] whole = Files.readAllBytes(file);
        Path truncated = tmp.resolve("truncated.dfb");
        Files.write(truncated, Arrays.copyOf(whole, whole.length - 200));

        assertThatThrownBy(() -> importer(dst, key, 0).importFrom(new ImportOptions(truncated, false))).isInstanceOf(BackupException.class);

        assertThat(new JdbcTemplate(dst).queryForObject(
                "SELECT CASE WHEN to_regclass('generation') IS NULL THEN 0 ELSE (SELECT count(*) FROM generation) END", Long.class)).isZero();
    }

    @Test
    void exportNeverOverwritesAnExistingFile() throws Exception {
        migrate(src, null);
        Path file = Files.writeString(tmp.resolve("exists.dfb"), "keep me");

        assertThatThrownBy(() -> exporter(src, key).export(new ExportOptions(file, true)))
                .isInstanceOf(BackupException.class).hasMessageContaining("exists.dfb");
        assertThat(Files.readString(file)).isEqualTo("keep me");
    }

    @Test
    void exportOfADatabaseWithoutSchemaIsRefusedAndWritesNothing() throws Exception {
        Path file = tmp.resolve("empty.dfb");

        assertThatThrownBy(() -> exporter(src, key).export(new ExportOptions(file, true))).isInstanceOf(BackupException.class);
        assertThat(file).doesNotExist();
    }

    // --- dati di prova -----------------------------------------------------------------------------------------------------

    /** Un po' di tutto: FK (anche su se' stessa), identity, bytea, double, numeric, json, vector, testi con i caratteri speciali di COPY. */
    private void populate() {
        JdbcTemplate j = new JdbcTemplate(src);
        String awkward = "prompt con tab\t, a capo\n, backslash \\ , \\N, virgolette \" ' e accenti: perche', citta', é中😀";
        j.update("""
                INSERT INTO generation (external_id, model, version, prompt, parameters_json, seed, status, kind, cost_usd, source_upload_filename,
                                        created_at, completed_at)
                VALUES ('ext-1', 'sdurz75/flux-lora-ff3', 'abc', ?, '{"num_outputs":2,"x":"y"}', 4242, 'SUCCEEDED', 'IMAGE', 0.012345, 'upload.png',
                        now(), now())""", awkward);
        long first = j.queryForObject("SELECT min(id) FROM generation", Long.class);
        j.update("""
                INSERT INTO generation (external_id, model, prompt, status, kind, source_generation_id, mask_upload_filename, created_at)
                VALUES ('ext-2', 'prunaai/p-video', 'animazione', 'SUCCEEDED', 'VIDEO', ?, 'mask.png', now())""", first);
        long second = j.queryForObject("SELECT max(id) FROM generation", Long.class);
        j.update("INSERT INTO generation_image (generation_id, ordinal, filename) VALUES (?, 0, 'f1.png'), (?, 1, 'f2.png'), (?, 0, 'f3.mp4')",
                first, first, second);
        j.update("INSERT INTO generation_favourite (generation_id, filename) VALUES (?, 'f1.png')", first);
        j.update("INSERT INTO generation_image_seed (generation_id, filename, seed) VALUES (?, 'f1.png', 7)", first);
        j.update("INSERT INTO generation_tag (generation_id, tag) VALUES (?, 'paesaggio')", first);
        j.update("INSERT INTO generation_file_tag (generation_id, filename, tag) VALUES (?, 'f2.png', 'scartata')", first);
        j.update("INSERT INTO chat_conversation (title, created_at, updated_at, generation_settings_json) VALUES ('Una chat', now(), now(), '{\"a\":1}')");
        long conversation = j.queryForObject("SELECT id FROM chat_conversation", Long.class);
        j.update("INSERT INTO chat_message (conversation_id, role, content, outcome_ref, created_at) VALUES (?, 'USER', ?, NULL, now()), (?, 'ASSISTANT', 'ok', ?, now())",
                conversation, awkward, conversation, first);
        j.update("INSERT INTO chat_conversation_tag (conversation_id, tag) VALUES (?, 'importante')", conversation);
        j.update("INSERT INTO lora_preset (name, source, scale, trigger_words, created_at, updated_at) VALUES ('mio', 'sdurz75/flux-lora-ff3', 0.7, 'ff3', now(), now())");
        j.update("UPDATE replicate_model SET active = false WHERE name = 'flux-krea-dev'");
        j.update("INSERT INTO system_event (created_at, last_seen_at, occurrences, source, severity, operation, error_type, message) VALUES (now(), now(), 3, 'REPLICATE', 'ERROR', 'op', 'X', 'm')");
        j.update("INSERT INTO api_token (provider, name, token_encrypted, token_hint, created_at, updated_at) VALUES ('HUGGINGFACE', 'hf', ?, '1234', now(), now())",
                (Object) new ChunkedAesGcmCipher(bytes(32, 5)).encryptBytes("hf_secret".getBytes()));
        j.update("""
                INSERT INTO vector_store (id, content, metadata, embedding)
                VALUES ('note:1', 'una nota', '{"type":"note","refId":1}', (SELECT ('[' || string_agg('0.25', ',') || ']')::vector FROM generate_series(1, 384)))""");

        put("f1.png", bytes(300_000, 1));
        put("f2.png", bytes(70_000, 2));
        put("f3.mp4", bytes(1_200_000, 3));
        put("upload.png", bytes(10, 4));
        put("mask.png", new byte[0]);
    }

    private void put(String name, byte[] content) {
        blobs.put(name, content);
        srcStorage.restore(name, new ByteArrayInputStream(content));
    }

    // --- verifiche ----------------------------------------------------------------------------------------------------------

    private void assertBlobsRestored() throws IOException {
        for (Map.Entry<String, byte[]> blob : blobs.entrySet()) {
            assertThat(dstStorage.size(blob.getKey())).as(blob.getKey()).hasValue(blob.getValue().length);
            try (var in = dstStorage.openRange(blob.getKey(), 0, blob.getValue().length)) {
                assertThat(in.readAllBytes()).as(blob.getKey()).isEqualTo(blob.getValue());
            }
        }
    }

    /** Per ogni tabella dati: "righe|digest" (md5 delle righe in forma testo, ordinate): colonne e valori devono coincidere. */
    private Map<String, String> digests(DataSource ds) {
        JdbcTemplate j = new JdbcTemplate(ds);
        Map<String, String> result = new LinkedHashMap<>();
        for (String table : j.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history' ORDER BY table_name""", String.class)) {
            result.put(table, j.queryForObject("SELECT count(*) || '|' || coalesce(md5(string_agg(t::text, E'\\n' ORDER BY t::text)), '') FROM \"" + table + "\" t",
                    String.class));
        }
        return result;
    }

    private void assertSequencesAreAheadOfTheData(DataSource ds) {
        JdbcTemplate j = new JdbcTemplate(ds);
        List<Map<String, Object>> identities = j.queryForList(
                "SELECT table_name, column_name FROM information_schema.columns WHERE table_schema = 'public' AND is_identity = 'YES'");
        assertThat(identities).isNotEmpty();
        for (Map<String, Object> identity : identities) {
            String table = (String) identity.get("table_name");
            String column = (String) identity.get("column_name");
            Long max = j.queryForObject("SELECT max(\"" + column + "\") FROM \"" + table + "\"", Long.class);
            Long next = j.queryForObject("SELECT nextval(pg_get_serial_sequence('\"" + table + "\"', '" + column + "'))", Long.class);
            assertThat(next).as("%s.%s", table, column).isGreaterThan(max == null ? 0 : max);
        }
    }

    private String latestVersion(DataSource ds) {
        return new JdbcTemplate(ds).queryForObject("SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1", String.class);
    }

    private String latestKnownVersion() {
        MigrationInfo[] all = Flyway.configure().locations(LOCATIONS).dataSource(dst).load().info().all();
        return all[all.length - 1].getVersion().getVersion();
    }

    private static List<Path> files(Path dir) {
        try (var stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- costruzione dei pezzi ----------------------------------------------------------------------------------------------

    private BackupExportService exporter(DataSource ds, String encryptionKey) {
        // Gli stessi bean che il server registra: ogni feature che possiede dei binari dichiara i suoi.
        ObjectProvider<IBlobReferences> references = new StaticListableBeanFactory(
                Map.of("generationReferences", new GenerationBlobReferences(), "trainingReferences", new TrainingBlobReferences()))
                .getBeanProvider(IBlobReferences.class);
        return new BackupExportService(new JdbcDatabaseDump(ds, messages), new ZipBackupArchive(messages), srcStorage, references, messages,
                "deep-flux", "local", encryptionKey);
    }

    private BackupImportService importer(DataSource ds, String encryptionKey, int undecryptableTokens) {
        return importer(ds, encryptionKey, undecryptableTokens, mock(ISystemEvents.class));
    }

    private BackupImportService importer(DataSource ds, String encryptionKey, int undecryptableTokens, ISystemEvents events) {
        return new BackupImportService(new ZipBackupArchive(messages), new JdbcDatabaseRestore(ds, LOCATIONS, messages), dstStorage,
                tokens(undecryptableTokens), events, messages, encryptionKey);
    }

    private static IApiTokens tokens(int undecryptable) {
        IApiTokens tokens = mock(IApiTokens.class);
        when(tokens.undecryptableCount()).thenReturn(undecryptable);
        return tokens;
    }

    /** WebDAV con la cache locale a 0 byte: e' la configurazione del profilo backup (storage.webdav.cache.max-size: 0). */
    private IImageStorageService webDavStorage(FakeWebDavServer dav, byte[] storageKey, ISystemEvents events, String cacheDir) throws IOException {
        WebDavBlobBackend backend = new WebDavBlobBackend(dav.base() + "/dav/", "user", "secret", Base64.getEncoder().encodeToString(storageKey),
                tmp.resolve(cacheDir).toString(), DataSize.ofBytes(0), messages, RestClient.builder(), events);
        return new ImageStorageService(backend, mock(IRemoteFileFetcher.class), messages);
    }

    private IImageStorageService storage(Path dir) {
        return new ImageStorageService(new LocalFsBlobBackend(dir.toString()), mock(IRemoteFileFetcher.class), messages);
    }

    private void migrate(DataSource ds, String target) {
        var configuration = Flyway.configure().dataSource(ds).locations(LOCATIONS);
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private DataSource freshDatabase(String name) throws Exception {
        try (Connection admin = DriverManager.getConnection(url, username, password); Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
            st.execute("CREATE DATABASE " + name);
        }
        return new DriverManagerDataSource(url.replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1"), username, password);
    }

    private static Messages realMessages() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasenames("messages", "messages-core", "messages-ai");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return new Messages(source);
    }

    private static byte[] bytes(int length, long seed) {
        byte[] b = new byte[length];
        new Random(seed).nextBytes(b);
        return b;
    }

    /** Come il ripristino vero, ma il commit fallisce: prova che il caricamento e' tutto o niente. */
    private record FailingAtCommit(IDatabaseRestore delegate) implements IDatabaseRestore {

        @Override
        public boolean isVirgin() {
            return delegate.isVirgin();
        }

        @Override
        public boolean knowsSchemaVersion(String version) {
            return delegate.knowsSchemaVersion(version);
        }

        @Override
        public void wipe() {
            delegate.wipe();
        }

        @Override
        public void migrateTo(String version) {
            delegate.migrateTo(version);
        }

        @Override
        public void migrateToLatest() {
            delegate.migrateToLatest();
        }

        @Override
        public Load beginLoad() {
            Load real = delegate.beginLoad();
            return new Load() {
                @Override
                public void truncateAll() {
                    real.truncateAll();
                }

                @Override
                public long copyIn(TableInfo table, java.io.InputStream data) {
                    return real.copyIn(table, data);
                }

                @Override
                public void resetSequences() {
                    real.resetSequences();
                }

                @Override
                public void commit() {
                    throw new IllegalStateException("commit fallito");
                }

                @Override
                public void close() {
                    real.close();
                }
            };
        }
    }
}
