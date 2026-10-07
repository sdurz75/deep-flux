package org.dual.hexa.ai.chat.adapter.out.searxng;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Sottoinsieme della risposta di GET /search?format=json che ci interessa. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SearxngResponse(List<SearchResult> results) {
}
