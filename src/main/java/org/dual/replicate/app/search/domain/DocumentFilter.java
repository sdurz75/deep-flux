package org.dual.replicate.app.search.domain;

import java.time.Instant;

/** Vincoli su una ricerca/listato; ogni campo {@code null} = nessun vincolo. {@code from}/{@code to} sul {@code createdAt} (estremi inclusi). */
public record DocumentFilter(String type, Instant from, Instant to) {

    public static final DocumentFilter NONE = new DocumentFilter(null, null, null);

    public static DocumentFilter ofType(String type) {
        return new DocumentFilter(type, null, null);
    }
}
