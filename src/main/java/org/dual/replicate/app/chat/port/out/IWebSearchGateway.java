package org.dual.replicate.app.chat.port.out;

import java.util.List;

import org.dual.replicate.app.chat.domain.WebSearchResult;

/** Ricerca sul web aperto (oggi SearXNG). */
public interface IWebSearchGateway {

    List<WebSearchResult> search(String query);
}
