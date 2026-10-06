package org.dual.replicate.core.backup.port.in;

import java.util.List;

import org.dual.replicate.core.backup.domain.BlobColumn;

/**
 * SPI: dice al backup dove il DB tiene i nomi dei binari dello storage. Il backend dei binari non ha un elenco (su WebDAV non c'e' un modo pratico
 * per scorrerlo), quindi i file da esportare sono quelli REFERENZIATI: per ogni colonna dichiarata il backup legge i valori distinti dentro lo
 * stesso snapshot del DB. La implementa chi possiede quelle colonne (nell'app: {@code generation}); senza nessuna implementazione si esporta solo il DB.
 * Come {@code ISearchableSource} e' una {@code port.in} implementata da un altro sottosistema, non un punto di estensione {@code port.out} del core.
 */
public interface IBlobReferences {

    List<BlobColumn> blobColumns();
}
