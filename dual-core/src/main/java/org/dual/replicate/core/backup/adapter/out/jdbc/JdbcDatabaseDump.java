package org.dual.replicate.core.backup.adapter.out.jdbc;

import java.io.IOException;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import javax.sql.DataSource;

import org.dual.replicate.core.backup.domain.BackupException;
import org.dual.replicate.core.backup.domain.BlobColumn;
import org.dual.replicate.core.backup.domain.TableInfo;
import org.dual.replicate.core.backup.port.out.IDatabaseDump;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Snapshot del DB per l'export, su PostgreSQL: UNA transazione REPEATABLE READ di sola lettura per tutto il lavoro e {@code COPY ... TO STDOUT}
 * in formato testo tramite il driver JDBC (niente {@code pg_dump}, che non e' nel jar e vorrebbe un client della stessa versione del server).
 * Il testo di {@code COPY} regge {@code vector}, {@code bytea}, {@code json} e {@code timestamptz} anche fra versioni diverse di PostgreSQL.
 */
@Component
@Profile("backup")
public class JdbcDatabaseDump implements IDatabaseDump {

    private final DataSource dataSource;
    private final Messages messages;

    public JdbcDatabaseDump(DataSource dataSource, Messages messages) {
        this.dataSource = dataSource;
        this.messages = messages;
    }

    @Override
    public Snapshot open() {
        Connection c = null;
        try {
            c = dataSource.getConnection();
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                // Prima istruzione della transazione; SET LOCAL finisce con lei, quindi la connessione torna al pool com'era.
                st.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY");
                st.execute("SET LOCAL TIME ZONE 'UTC'");
            }
            return new JdbcSnapshot(c);
        } catch (SQLException e) {
            JdbcSnapshot.release(c);
            throw new BackupException(messages.get("backup.error.database", e.getMessage()), e);
        }
    }

    private final class JdbcSnapshot implements Snapshot {

        private final Connection connection;

        JdbcSnapshot(Connection connection) {
            this.connection = connection;
        }

        @Override
        public String schemaVersion() {
            try (Statement st = connection.createStatement()) {
                try (ResultSet rs = st.executeQuery("SELECT to_regclass('" + JdbcSchema.FLYWAY_HISTORY + "') IS NOT NULL")) {
                    rs.next();
                    if (!rs.getBoolean(1)) {
                        throw new BackupException(messages.get("backup.error.noSchema"));
                    }
                }
                try (ResultSet rs = st.executeQuery("SELECT version FROM " + JdbcSchema.FLYWAY_HISTORY
                        + " WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1")) {
                    if (!rs.next()) {
                        throw new BackupException(messages.get("backup.error.noSchema"));
                    }
                    return rs.getString(1);
                }
            } catch (SQLException e) {
                throw database(e);
            }
        }

        @Override
        public List<TableInfo> tables() {
            try {
                return JdbcSchema.tables(connection, messages);
            } catch (SQLException e) {
                throw database(e);
            }
        }

        /**
         * Una colonna che nello schema della SORGENTE non c'e' ancora (un DB piu' vecchio del jar che esporta: il profilo backup non migra) non
         * puo' referenziare nessun file, quindi vale "nessun valore". Si controlla PRIMA di interrogarla: in PostgreSQL una query fallita
         * aborta l'intera transazione dello snapshot, e con lei il resto dell'export.
         */
        @Override
        public Set<String> distinctValues(BlobColumn column) {
            Set<String> values = new TreeSet<>();
            try {
                if (!columnExists(column)) {
                    return values;
                }
                String sql = "SELECT DISTINCT " + JdbcSchema.quote(column.column()) + " FROM " + JdbcSchema.quote(column.table())
                        + " WHERE " + JdbcSchema.quote(column.column()) + " IS NOT NULL";
                try (PreparedStatement st = connection.prepareStatement(sql); ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        values.add(rs.getString(1));
                    }
                }
                return values;
            } catch (SQLException e) {
                throw database(e);
            }
        }

        private boolean columnExists(BlobColumn column) throws SQLException {
            try (PreparedStatement st = connection.prepareStatement("""
                    SELECT 1 FROM information_schema.columns
                    WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?""")) {
                st.setString(1, column.table());
                st.setString(2, column.column());
                try (ResultSet rs = st.executeQuery()) {
                    return rs.next();
                }
            }
        }

        @Override
        public long copyOut(TableInfo table, OutputStream out) {
            try {
                return connection.unwrap(PGConnection.class).getCopyAPI().copyOut(
                        "COPY " + JdbcSchema.quote(table.name()) + " (" + JdbcSchema.columnList(table) + ") TO STDOUT", out);
            } catch (SQLException e) {
                throw database(e);
            } catch (IOException e) {
                throw new BackupException(messages.get("backup.error.io", e.getMessage()), e);
            }
        }

        @Override
        public void close() {
            release(connection);
        }

        private BackupException database(SQLException e) {
            return new BackupException(messages.get("backup.error.database", e.getMessage()), e);
        }

        static void release(Connection c) {
            if (c == null) {
                return;
            }
            try {
                c.rollback();
                c.setAutoCommit(true);
            } catch (SQLException e) {
                LoggerHolder.log.debug("Chiusura dello snapshot", e);
            } finally {
                try {
                    c.close();
                } catch (SQLException e) {
                    LoggerHolder.log.debug("Chiusura della connessione", e);
                }
            }
        }
    }

    private static final class LoggerHolder {
        static final Logger log = LoggerFactory.getLogger(JdbcDatabaseDump.class);
    }
}
