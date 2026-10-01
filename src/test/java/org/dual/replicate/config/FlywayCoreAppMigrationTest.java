package org.dual.replicate.config;

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

    private static final String[] LOCATIONS = {"classpath:db/migration/core", "classpath:db/migration/app"};

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
        assertThat(first.migrationsExecuted).isEqualTo(2);

        Files.writeString(later.resolve("V2026_12_01_0900__core_probe.sql"), "CREATE TABLE core_probe (id int);");
        var second = Flyway.configure().dataSource(probeUrl, username, password)
                .locations(LOCATIONS[0], LOCATIONS[1], "filesystem:" + later).load();
        assertThat(second.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(second.validateWithResult().validationSuccessful).isTrue();

        try (Connection c = DriverManager.getConnection(probeUrl, username, password); Statement st = c.createStatement()) {
            var tables = st.executeQuery("select count(*) from information_schema.tables where table_name in "
                    + "('system_event','api_token','generation','vector_store','core_probe')");
            assertThat(tables.next()).isTrue();
            assertThat(tables.getInt(1)).isEqualTo(5);
        }
    }
}
