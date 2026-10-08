package org.dual.hexa.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le migrazioni core e app sono UNA sola sequenza a timestamp: su un database nuovo si applicano entrambe, e una migrazione core
 * piu' recente aggiunta DOPO (un aggiornamento del template) si applica sopra senza errori di ordine. Gira su un database dedicato
 * dello stesso container dei test (mai quello di sviluppo).
 */
@SpringBootTest
class FlywayCoreAppMigrationTest {

    private static final String[] LOCATIONS = {"classpath:db/migration/core", "classpath:db/migration/ai", "classpath:db/migration/app"};

    @Value("${spring.datasource.url}")
    private String url;
    @Value("${spring.datasource.username}")
    private String username;
    @Value("${spring.datasource.password}")
    private String password;

    @Test
    void coreAndAppApplyInOrderAndALaterCoreMigrationStacksOnTop(@TempDir Path later) throws Exception {
        String database = "flyway_probe";
        try (Connection admin = DriverManager.getConnection(url, username, password); Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + database);
            st.execute("CREATE DATABASE " + database);
        }
        String probeUrl = url.replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");

        var first = Flyway.configure().dataSource(probeUrl, username, password).locations(LOCATIONS).load().migrate();
        // core baseline + app_lock + module_config (core) + ai baseline + app baseline + flux_lora_finetune_form_type + generation_image_seed + flux_fill_dev_inpainting + generation_source_image_filename + flux_fill_pro + replicate_balance_anchor + imported_images + tags + training_dataset + training_run + training_result + lora_preset_default_token + tokens_to_secrets (core) + secret_columns (app) + tokens_to_secrets (core) + secret_columns (app)
        assertThat(first.migrationsExecuted).isEqualTo(19);

        Files.writeString(later.resolve("V2026_12_01_0900__core_probe.sql"), "CREATE TABLE core_probe (id int);");
        var second = Flyway.configure().dataSource(probeUrl, username, password)
                .locations(LOCATIONS[0], LOCATIONS[1], LOCATIONS[2], "filesystem:" + later).load();
        assertThat(second.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(second.validateWithResult().validationSuccessful).isTrue();

        try (Connection c = DriverManager.getConnection(probeUrl, username, password); Statement st = c.createStatement()) {
            var tables = st.executeQuery("select count(*) from information_schema.tables where table_name in "
                    + "('system_event','secret','generation','vector_store','core_probe')");
            assertThat(tables.next()).isTrue();
            assertThat(tables.getInt(1)).isEqualTo(5);
        }
    }

    /**
     * I dati di prima dei «segreti»: un token in {@code api_token}, un evento {@code TOKENS} con subject {@code token:<id>}, un LoRA col token di default e un
     * dataset col token HF. Dopo la migrazione sono un segreto dello stesso tipo, l'evento punta a {@code secret:<id>} e le colonne dell'app hanno il nome nuovo.
     * E' lo stesso ordine di un import di backup vecchio: il {@code COPY} entra coi nomi vecchi, il rinomino gira dopo.
     */
    @Test
    void existingTokensEventsAndAppReferencesSurviveTheRenameToSecrets() throws Exception {
        String database = "flyway_rename_probe";
        try (Connection admin = DriverManager.getConnection(url, username, password); Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + database);
            st.execute("CREATE DATABASE " + database);
        }
        String probeUrl = url.replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");

        Flyway.configure().dataSource(probeUrl, username, password).locations(LOCATIONS).target("2026.10.08.1100").load().migrate();
        try (Connection c = DriverManager.getConnection(probeUrl, username, password); Statement st = c.createStatement()) {
            st.execute("INSERT INTO api_token (provider, name, token_encrypted, token_hint, created_at, updated_at) VALUES ('HUGGINGFACE', 'hf', '\\x01', 'abcd', now(), now())");
            st.execute("INSERT INTO system_event (created_at, last_seen_at, occurrences, source, severity, operation, error_type, message, subject) "
                    + "VALUES (now(), now(), 1, 'TOKENS', 'WARNING', 'tokenExpiring', 'W', 'm', 'token:' || (SELECT id FROM api_token))");
            st.execute("INSERT INTO lora_preset (name, source, scale, created_at, updated_at, default_token_id) VALUES ('l', 's', 1, now(), now(), (SELECT id FROM api_token))");
            st.execute("INSERT INTO training_dataset (name, trigger_word, lora_type, created_at, updated_at, hf_token_id) VALUES ('d', 'tw', 'style', now(), now(), (SELECT id FROM api_token))");
        } catch (java.sql.SQLException e) {
            throw new AssertionError("dati di partenza: " + e.getMessage(), e);
        }

        Flyway.configure().dataSource(probeUrl, username, password).locations(LOCATIONS).load().migrate();

        try (Connection c = DriverManager.getConnection(probeUrl, username, password); Statement st = c.createStatement()) {
            var secret = st.executeQuery("SELECT type, name, hint, id FROM secret");
            assertThat(secret.next()).isTrue();
            assertThat(secret.getString("type")).isEqualTo("HUGGINGFACE");
            assertThat(secret.getString("hint")).isEqualTo("abcd");
            long id = secret.getLong("id");
            var event = st.executeQuery("SELECT source, subject FROM system_event");
            assertThat(event.next()).isTrue();
            assertThat(event.getString("source")).isEqualTo("SECRETS");
            assertThat(event.getString("subject")).isEqualTo("secret:" + id);
            var lora = st.executeQuery("SELECT default_secret_id FROM lora_preset");
            assertThat(lora.next()).isTrue();
            assertThat(lora.getLong(1)).isEqualTo(id);
            var dataset = st.executeQuery("SELECT hf_secret_id FROM training_dataset");
            assertThat(dataset.next()).isTrue();
            assertThat(dataset.getLong(1)).isEqualTo(id);
            var gone = st.executeQuery("select count(*) from information_schema.tables where table_name = 'api_token'");
            assertThat(gone.next()).isTrue();
            assertThat(gone.getInt(1)).isZero();
        }
    }
}
