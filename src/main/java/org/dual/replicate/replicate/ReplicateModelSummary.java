package org.dual.replicate.replicate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Sottoinsieme di un modello dentro una collection Replicate (GET /collections/{slug}). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReplicateModelSummary(String owner, String name, String description) {

    /** "owner/name", la forma richiesta da ReplicateClient.createPrediction. */
    public String id() {
        return owner + "/" + name;
    }
}
