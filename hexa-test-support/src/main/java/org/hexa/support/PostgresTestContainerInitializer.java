package org.hexa.support;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Un PostgreSQL+pgvector usa-e-getta per TUTTA la suite: container singleton avviato alla prima richiesta e lasciato a Ryuk, che lo
 * toglie a fine JVM. Registrato in {@code META-INF/spring.factories}, cosi' ogni {@code @SpringBootTest} lo eredita senza
 * annotazioni per classe (come il profilo "test" di Surefire) e nessun test puo' toccare il database di sviluppo. Richiede Docker.
 * Flyway applica lo schema reale (core + app, con l'estensione {@code vector}) alla prima partenza di un contesto; i contesti successivi
 * ritrovano lo stesso database gia' migrato.
 */
public class PostgresTestContainerInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        synchronized (POSTGRES) {
            if (!POSTGRES.isRunning()) {
                // Ogni contesto Spring tenuto in cache apre un pool Hikari da 10 connessioni: oltre una decina di contesti diversi il default di PostgreSQL
                // (max_connections = 100) finisce e i test falliscono a caso con "too many clients already" (dipende da quale contesto e' vivo).
                POSTGRES.withCommand("postgres", "-c", "max_connections=400");
                POSTGRES.start();
            }
        }
        TestPropertyValues.of(
                "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "spring.datasource.username=" + POSTGRES.getUsername(),
                "spring.datasource.password=" + POSTGRES.getPassword()).applyTo(context);
    }
}
