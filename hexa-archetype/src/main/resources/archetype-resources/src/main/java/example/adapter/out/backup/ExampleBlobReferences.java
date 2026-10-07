package ${package}.example.adapter.out.backup;

import java.util.List;
import org.dual.hexa.core.backup.domain.BlobColumn;
import org.dual.hexa.core.backup.port.in.IBlobReferences;
import org.springframework.stereotype.Component;

/**
 * Dove il DB tiene i nomi dei binari di questa feature. Il backup (`export`/`import` del jar) esporta SOLO i file referenziati da queste colonne: il
 * backend dei binari non ha un elenco. Una colonna con un filename aggiunta da una migrazione e non dichiarata qui fa fallire {@code BackupBlobColumnsTest}.
 */
@Component
public class ExampleBlobReferences implements IBlobReferences {

    @Override
    public List<BlobColumn> blobColumns() {
        return List.of(new BlobColumn("example_item", "attachment_filename"));
    }
}
