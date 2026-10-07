package org.dual.hexa.ai.chat.port.out;

import java.util.List;

import org.dual.hexa.ai.chat.domain.WebSearchResult;

/** Ricerca sul web aperto (oggi SearXNG). */
public interface IWebSearchGateway {

    List<WebSearchResult> search(String query);
}
