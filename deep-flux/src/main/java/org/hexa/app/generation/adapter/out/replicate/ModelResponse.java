package org.hexa.app.generation.adapter.out.replicate;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.hexa.app.generation.domain.ModelVersion;

/** Sottoinsieme della risposta di GET /v1/models/{owner}/{name}: l'ultima versione e il suo schema OpenAPI (per i nomi dei campi di input). */
@JsonIgnoreProperties(ignoreUnknown = true)
record ModelResponse(@JsonProperty("latest_version") LatestVersion latestVersion) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LatestVersion(String id, @JsonProperty("openapi_schema") Map<String, Object> openapiSchema) {
    }

    /** Vuoto se il modello non ha ancora versioni pubblicate. */
    Optional<ModelVersion> toLatestVersion() {
        if (latestVersion == null || latestVersion.id() == null || latestVersion.id().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new ModelVersion(latestVersion.id(), inputFields(latestVersion.openapiSchema())));
    }

    /** components.schemas.Input.properties dello schema, vuoto se la forma e' inattesa. */
    private static Set<String> inputFields(Map<String, Object> schema) {
        return child(child(child(child(schema, "components"), "schemas"), "Input"), "properties").keySet();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> child(Map<String, Object> node, String key) {
        return node != null && node.get(key) instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
