package org.hexa.core.backup.domain;

import java.util.List;

/** Una tabella del backup con le colonne nell'ordine in cui e' scritta (il {@code COPY} le elenca: non dipende dall'ordine fisico della destinazione). */
public record TableInfo(String name, List<String> columns) {

    public TableInfo {
        columns = List.copyOf(columns);
    }
}
