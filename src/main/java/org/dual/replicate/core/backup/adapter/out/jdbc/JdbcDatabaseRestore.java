package org.dual.replicate.core.backup.adapter.out.jdbc;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.dual.replicate.core.backup.domain.BackupException;
import org.dual.replicate.core.backup.domain.TableInfo;
import org.dual.replicate.core.backup.port.out.IDatabaseRestore;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Scrittura del DB per l'import, su PostgreSQL: lo schema lo portano le STESSE migrazioni dell'app (Flyway da codice, con
 * {@code spring.flyway.locations}: nel profilo {@code backup} l'auto-migrazione e' spenta), i dati entrano con {@code COPY ... FROM STDIN}.
 */
@Component
@Profile("backup")
public class JdbcDatabaseRestore implements IDatabaseRestore {

    private static final Logger log = LoggerFactory.getLogger(JdbcDatabaseRestore.class);

    private final DataSource dataSource;
    private final String[] locations;
    private final Messages messages;

    public JdbcDatabaseRestore(DataSource dataSource, @Value("${spring.flyway.locations}") String[] locations, Messages messages) {
        this.dataSource = dataSource;
        this.locations = locations;
        this.messages = messages;
    }

    @Override
    public boolean isVirgin() {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT to_regclass('" + JdbcSchema.FLYWAY_HISTORY + "') IS NULL")) {
            rs.next();
            return rs.getBoolean(1);
        } catch (SQLException e) {
            throw database(e);
        }
    }

    @Override
    public boolean knowsSchemaVersion(String version) {
        for (MigrationInfo migration : flyway(null).info().all()) {
            if (migration.getVersion() != null && migration.getVersion().getVersion().equals(version)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void wipe() {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            String schema;
            try (ResultSet rs = st.executeQuery("SELECT current_schema()")) {
                rs.next();
                schema = JdbcSchema.quote(rs.getString(1));
            }
            st.execute("DROP SCHEMA " + schema + " CASCADE");
            st.execute("CREATE SCHEMA " + schema);
        } catch (SQLException e) {
            throw database(e);
        }
    }

    @Override
    public void migrateTo(String version) {
        flyway(MigrationVersion.fromVersion(version)).migrate();
    }

    @Override
    public void migrateToLatest() {
        flyway(null).migrate();
    }

    private Flyway flyway(MigrationVersion target) {
        FluentConfiguration configuration = Flyway.configure(JdbcDatabaseRestore.class.getClassLoader())
                .dataSource(dataSource).locations(locations);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    @Override
    public Load beginLoad() {
        Connection c = null;
        try {
            c = dataSource.getConnection();
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SET LOCAL TIME ZONE 'UTC'");
            }
            return new JdbcLoad(c);
        } catch (SQLException e) {
            JdbcLoad.release(c);
            throw database(e);
        }
    }

    private BackupException database(SQLException e) {
        return new BackupException(messages.get("backup.error.database", e.getMessage()), e);
    }

    private final class JdbcLoad implements Load {

        private final Connection connection;
        private boolean committed;

        JdbcLoad(Connection connection) {
            this.connection = connection;
        }

        @Override
        public void truncateAll() {
            try (Statement st = connection.createStatement()) {
                List<String> names = JdbcSchema.tables(connection, messages).stream()
                        .map(t -> JdbcSchema.quote(t.name())).collect(Collectors.toList());
                if (!names.isEmpty()) {
                    st.execute("TRUNCATE TABLE " + String.join(", ", names) + " RESTART IDENTITY CASCADE");
                }
            } catch (SQLException e) {
                throw database(e);
            }
        }

        @Override
        public long copyIn(TableInfo table, InputStream data) {
            try {
                return connection.unwrap(PGConnection.class).getCopyAPI().copyIn(
                        "COPY " + JdbcSchema.quote(table.name()) + " (" + JdbcSchema.columnList(table) + ") FROM STDIN", data);
            } catch (SQLException e) {
                throw database(e);
            } catch (IOException e) {
                throw new BackupException(messages.get("backup.error.io", e.getMessage()), e);
            }
        }

        @Override
        public void resetSequences() {
            try {
                for (String[] identity : JdbcSchema.identityColumns(connection)) {
                    String table = JdbcSchema.quote(identity[0]);
                    String column = JdbcSchema.quote(identity[1]);
                    Long max;
                    try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(
                            "SELECT max(" + column + ") FROM " + table)) {
                        rs.next();
                        long value = rs.getLong(1);
                        max = rs.wasNull() ? null : value;
                    }
                    // Senza righe la sequenza riparte da 1 (is_called = false); con righe il prossimo valore e' max + 1.
                    try (PreparedStatement st = connection.prepareStatement("SELECT setval(pg_get_serial_sequence(?, ?), ?, ?)")) {
                        st.setString(1, table);
                        st.setString(2, identity[1]);
                        st.setLong(3, max == null ? 1 : max);
                        st.setBoolean(4, max != null);
                        st.execute();
                    }
                }
            } catch (SQLException e) {
                throw database(e);
            }
        }

        @Override
        public void commit() {
            try {
                connection.commit();
                committed = true;
            } catch (SQLException e) {
                throw database(e);
            }
        }

        @Override
        public void close() {
            if (!committed) {
                try {
                    connection.rollback();
                } catch (SQLException e) {
                    log.debug("Rollback del caricamento", e);
                }
            }
            release(connection);
        }

        static void release(Connection c) {
            if (c == null) {
                return;
            }
            try {
                c.setAutoCommit(true);
            } catch (SQLException e) {
                log.debug("Ripristino dell'autocommit", e);
            }
            try {
                c.close();
            } catch (SQLException e) {
                log.debug("Chiusura della connessione", e);
            }
        }
    }
}
