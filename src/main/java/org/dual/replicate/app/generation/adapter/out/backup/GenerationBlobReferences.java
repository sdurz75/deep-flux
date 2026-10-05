package org.dual.replicate.app.generation.adapter.out.backup;

import java.util.List;

import org.dual.replicate.core.backup.domain.BlobColumn;
import org.dual.replicate.core.backup.port.in.IBlobReferences;
import org.springframework.stereotype.Component;

/**
 * Dove il DB tiene i nomi dei binari: i file prodotti da una generazione e, per le immagini caricate dall'utente (sorgente di un img2video/edit, maschera
 * di inpainting), i nomi sulla riga della generazione. Le altre colonne con un filename (preferiti, seed, tag per file, {@code source_image_filename})
 * ripetono nomi che stanno gia' in {@code generation_image}: {@code BackupBlobColumnsTest} fa fallire una colonna nuova non dichiarata qui.
 */
@Component
public class GenerationBlobReferences implements IBlobReferences {

    @Override
    public List<BlobColumn> blobColumns() {
        return List.of(
                new BlobColumn("generation_image", "filename"),
                new BlobColumn("generation", "source_upload_filename"),
                new BlobColumn("generation", "mask_upload_filename"));
    }
}
