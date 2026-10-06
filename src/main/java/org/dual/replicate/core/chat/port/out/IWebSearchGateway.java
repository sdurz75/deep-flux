package org.dual.replicate.core.chat.port.out;

import java.util.List;

import org.dual.replicate.core.chat.domain.WebSearchResult;

/** Ricerca sul web aperto (oggi SearXNG). */
public interface IWebSearchGateway {

    List<WebSearchResult> search(String query);
}
