package org.hexa.core.chat.adapter.out.searxng;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Sottoinsieme di un elemento di "results" nella risposta JSON di SearXNG. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SearchResult(String title, String url, String content) {
}
