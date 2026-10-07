package org.hexa.app.generation.adapter.out.backup;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.hexa.app.training.adapter.out.backup.TrainingBlobReferences;
import org.hexa.core.backup.domain.BlobColumn;
import org.hexa.core.backup.port.in.IBlobReferences;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Il backup esporta i binari che il DB referenzia, e sono i bean {@link IBlobReferences} (uno per feature: {@code GenerationBlobReferences},
 * {@code TrainingBlobReferences}) a dire dove sono. Una colonna con un filename aggiunta da una migrazione futura e dimenticata li' farebbe sparire dal
 * backup in silenzio: questo test obbliga a decidere se e' un riferimento nuovo (da dichiarare) o una copia di nomi che stanno gia' altrove.
 */
@SpringBootTest
class BackupBlobColumnsTest {

    /** Colonne con un filename che NON introducono file nuovi: ripetono nomi di {@code generation_image} (file o scelte su quei file). */
    private static final Set<String> DERIVED = Set.of(
            "generation_favourite.filename",
            "generation_image_seed.filename",
            "generation_file_tag.filename",
            "generation.source_image_filename");

    @Autowired
    private JdbcTemplate jdbc;

    /** TUTTI i bean registrati, come li vede l'export: se una feature ne aggiunge uno, finisce qui da solo. */
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
    void declaresTheFilesAGenerationOwns() {
        assertThat(new GenerationBlobReferences().blobColumns()).containsExactlyInAnyOrder(
                new BlobColumn("generation_image", "filename"),
                new BlobColumn("generation", "source_upload_filename"),
                new BlobColumn("generation", "mask_upload_filename"));
    }

    @Test
    void declaresTheFilesATrainingDatasetOwns() {
        assertThat(new TrainingBlobReferences().blobColumns()).containsExactlyInAnyOrder(
                new BlobColumn("training_image", "filename"),
                new BlobColumn("training_image", "original_filename"));
    }
}
