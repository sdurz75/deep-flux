package org.hexa.core.chat.port.out;

import java.util.List;

import org.hexa.core.chat.domain.WebSearchResult;

/** Ricerca sul web aperto (oggi SearXNG). */
public interface IWebSearchGateway {

    List<WebSearchResult> search(String query);
}
