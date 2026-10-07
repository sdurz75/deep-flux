package org.hexa.app.training.port.out;

import java.util.List;

import org.hexa.app.training.domain.ArchiveItem;
import org.hexa.app.training.domain.DatasetArchive;

/** Costruisce lo zip del dataset (immagini + didascalie) leggendo i file dallo storage. Lo use case non vede ne' file ne' stream di scrittura. */
public interface IDatasetArchiver {

    /** Lo zip, in un file temporaneo che si elimina chiudendo l'archivio. @throws org.hexa.app.training.domain.TrainingException se un file non si legge */
    DatasetArchive build(List<ArchiveItem> items);
}
