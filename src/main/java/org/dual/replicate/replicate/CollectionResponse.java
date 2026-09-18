package org.dual.replicate.replicate;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Sottoinsieme della risposta di GET /v1/collections/{slug} che ci interessa. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CollectionResponse(String name, String slug, List<ReplicateModelSummary> models) {
}
