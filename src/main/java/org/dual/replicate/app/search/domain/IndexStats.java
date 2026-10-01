package org.dual.replicate.app.search.domain;

import java.util.Map;

/** Numeri della testata di /search. */
public record IndexStats(long total, Map<String, Long> counts, String modelId, int dimensions, boolean running) {
}
