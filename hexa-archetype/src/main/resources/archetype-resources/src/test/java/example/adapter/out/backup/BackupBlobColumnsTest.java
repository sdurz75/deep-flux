package ${package}.example.adapter.out.backup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.hexa.core.backup.domain.BlobColumn;
import org.hexa.core.backup.port.in.IBlobReferences;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Il backup esporta i binari che il DB referenzia, e sono i bean {@code IBlobReferences} a dire dove sono. Una colonna con un filename aggiunta da una
 * migrazione futura e dimenticata li' farebbe sparire dal backup in silenzio: questo test obbliga a decidere se e' un riferimento nuovo (da dichiarare)
 * o una copia di nomi che stanno gia' altrove (da elencare in {@code DERIVED}).
 */
@SpringBootTest
class BackupBlobColumnsTest {

    /** Colonne con un filename che NON introducono file nuovi (ripetono nomi gia' dichiarati altrove). Vuoto finche' non ne nasce una. */
    private static final Set<String> DERIVED = Set.of();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private List<IBlobReferences> references;

    @Test
    void everyColumnHoldingAFilenameIsDeclaredOrKnownToBeDerived() {
        Set<String> inSchema = new HashSet<>(jdbc.queryForList("""
                SELECT table_name || '.' || column_name FROM information_schema.columns
                WHERE table_schema = 'public' AND column_name LIKE '%filename%' AND table_name <> 'flyway_schema_history'""", String.class));
        Set<String> declared = new HashSet<>();
        for (IBlobReferences feature : references) {
            for (BlobColumn column : feature.blobColumns()) {
                declared.add(column.table() + "." + column.column());
            }
        }

        assertThat(declared).as("colonne dichiarate che non esistono piu'").isSubsetOf(inSchema);
        Set<String> undeclared = new HashSet<>(inSchema);
        undeclared.removeAll(declared);
        undeclared.removeAll(DERIVED);
        assertThat(undeclared).as("colonne con un filename ne' dichiarate in un IBlobReferences ne' note come derivate").isEmpty();
    }

    @Test
    void theExampleDeclaresItsAttachmentColumn() {
        assertThat(new ExampleBlobReferences().blobColumns()).containsExactly(new BlobColumn("example_item", "attachment_filename"));
    }
}
