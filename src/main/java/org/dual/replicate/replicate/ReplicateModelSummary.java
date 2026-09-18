package org.dual.replicate.replicate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Sottoinsieme di un modello dentro una collection Replicate (GET /collections/{slug}) o di GET /models/{owner}/{name}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReplicateModelSummary(
        String owner,
        String name,
        String description,
        @JsonProperty("latest_version") LatestVersion latestVersion) {

    /** "owner/name", la forma richiesta da ReplicateClient.createPrediction. */
    public String id() {
        return owner + "/" + name;
    }

    /**
     * Hash della versione pubblicata piu' recente, se nota (null altrimenti:
     * es. un modello senza versioni pubblicate). Necessario perche' non
     * tutti i modelli supportano lo shortcut "/models/{owner}/{name}/predictions"
     * che usa implicitamente l'ultima versione: verificato dal vivo che
     * per i modelli personali (account sdurz75, LoRA da training) quello
     * shortcut risponde 404 anche se il modello esiste ed e' pubblico —
     * serve passare esplicitamente questo hash come "version".
     */
    public String latestVersionId() {
        return latestVersion == null ? null : latestVersion.id();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LatestVersion(String id) {
    }
}
