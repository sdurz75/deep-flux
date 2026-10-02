package org.dual.replicate.app.search.domain;

import java.time.Instant;

/**
 * Vincoli su una ricerca/listato; ogni campo {@code null}/{@code false} = nessun vincolo. {@code from}/{@code to} sul {@code createdAt}
 * (estremi inclusi). {@code kind} ({@code IMAGE}/{@code VIDEO}) e {@code favouriteOnly} valgono solo per i documenti che portano quei
 * metadata (le generazioni): gli altri tipi non combaciano.
 */
public record DocumentFilter(String type, Instant from, Instant to, String kind, boolean favouriteOnly) {

    public static final DocumentFilter NONE = new DocumentFilter(null, null, null, null, false);

    public DocumentFilter(String type, Instant from, Instant to) {
        this(type, from, to, null, false);
    }

    public static DocumentFilter ofType(String type) {
        return new DocumentFilter(type, null, null);
    }
}
