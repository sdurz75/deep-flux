package org.dual.replicate.core.backup.adapter.out.jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.dual.replicate.core.backup.domain.BackupException;
import org.dual.replicate.core.backup.domain.TableInfo;
import org.dual.replicate.core.backup.domain.TableOrder;
import org.dual.replicate.core.kernel.i18n.Messages;

/**
 * Cosa c'e' nello schema corrente, letto da {@code information_schema}/{@code pg_constraint}: il backup non conosce le tabelle dell'app (le scopre),
 * quindi resta generico. La cronologia di Flyway non e' un dato: si ricrea dalle migrazioni.
 */
final class JdbcSchema {

    static final String FLYWAY_HISTORY = "flyway_schema_history";

    private JdbcSchema() {
    }

    /** Identificatore tra virgolette (raddoppia quelle interne). */
    static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    static String columnList(TableInfo table) {
        return table.columns().stream().map(JdbcSchema::quote).collect(Collectors.joining(", "));
    }

    /** Le tabelle dati con le colonne caricabili, in ordine di caricamento (le referenziate da una FK prima di chi le referenzia). */
    static List<TableInfo> tables(Connection c, Messages messages) throws SQLException {
        Map<String, List<String>> columns = new LinkedHashMap<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("""
                SELECT t.table_name, c.column_name
                FROM information_schema.tables t
                JOIN information_schema.columns c ON c.table_schema = t.table_schema AND c.table_name = t.table_name
                WHERE t.table_schema = current_schema() AND t.table_type = 'BASE TABLE' AND t.table_name <> 'flyway_schema_history'
                  AND c.is_generated = 'NEVER'
                ORDER BY t.table_name, c.ordinal_position""")) {
            while (rs.next()) {
                columns.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
            }
        }
        Map<String, Set<String>> parents = new LinkedHashMap<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("""
                SELECT child.relname, parent.relname
                FROM pg_constraint k
                JOIN pg_class child ON child.oid = k.conrelid
                JOIN pg_class parent ON parent.oid = k.confrelid
                JOIN pg_namespace n ON n.oid = child.relnamespace
                WHERE k.contype = 'f' AND n.nspname = current_schema()""")) {
            while (rs.next()) {
                parents.computeIfAbsent(rs.getString(1), k -> new LinkedHashSet<>()).add(rs.getString(2));
            }
        }
        List<String> order;
        try {
            order = TableOrder.sort(columns.keySet(), parents);
        } catch (IllegalStateException e) {
            throw new BackupException(messages.get("backup.error.cycle", e.getMessage()), e);
        }
        List<TableInfo> tables = new ArrayList<>();
        for (String name : order) {
            tables.add(new TableInfo(name, columns.get(name)));
        }
        return tables;
    }

    /** Le colonne {@code GENERATED ... AS IDENTITY}: (tabella, colonna). */
    static List<String[]> identityColumns(Connection c) throws SQLException {
        List<String[]> identities = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("""
                SELECT table_name, column_name FROM information_schema.columns
                WHERE table_schema = current_schema() AND is_identity = 'YES' AND table_name <> 'flyway_schema_history'
                ORDER BY table_name, ordinal_position""")) {
            while (rs.next()) {
                identities.add(new String[] {rs.getString(1), rs.getString(2)});
            }
        }
        return identities;
    }
}
