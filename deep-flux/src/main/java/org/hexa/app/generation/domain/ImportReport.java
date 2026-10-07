package org.hexa.app.generation.domain;

import java.util.List;

/** L'esito di un'importazione di piu' file: un {@link ImportResult} per file, nell'ordine in cui sono arrivati. */
public record ImportReport(List<ImportResult> results) {

    public ImportReport {
        results = List.copyOf(results);
    }

    public long acceptedCount() {
        return results.stream().filter(ImportResult::isAccepted).count();
    }

    public long rejectedCount() {
        return results.size() - acceptedCount();
    }
}
