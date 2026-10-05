package org.dual.replicate.app.training.adapter.out.backup;

import java.util.List;

import org.dual.replicate.core.backup.domain.BlobColumn;
import org.dual.replicate.core.backup.port.in.IBlobReferences;
import org.springframework.stereotype.Component;

/**
 * Dove il DB tiene i nomi dei binari dei dataset di addestramento: l'immagine che va nello zip e l'originale da cui si rifa' il ritaglio (due file distinti
 * solo dopo un ritaglio, ma entrambe le colonne sono sempre piene). Vale anche per gli snapshot dei training: stessa tabella.
 */
@Component
public class TrainingBlobReferences implements IBlobReferences {

    @Override
    public List<BlobColumn> blobColumns() {
        return List.of(
                new BlobColumn("training_image", "filename"),
                new BlobColumn("training_image", "original_filename"));
    }
}
