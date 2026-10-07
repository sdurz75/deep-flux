package org.hexa.core.credits.adapter.out.openrouter;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Sottoinsieme della risposta di {@code GET /api/v1/credits}: {@code {"data":{"total_credits":..,"total_usage":..}}}. */
@JsonIgnoreProperties(ignoreUnknown = true)
record CreditsResponse(Data data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Data(@JsonProperty("total_credits") BigDecimal totalCredits, @JsonProperty("total_usage") BigDecimal totalUsage) {
    }
}
