package org.hexa.core.events.port.out;

import java.util.List;

import org.hexa.core.events.domain.EventLink;

/**
 * Punto di estensione: l'app traduce il {@code subject} di un evento ({@code generation:12}) in link alla propria pagina. Il core
 * funziona anche senza nessuna implementazione (nessun link), perche' lo inietta come {@code ObjectProvider}.
 */
public interface IEventLinkResolver {

    /** I link per {@code subject}, vuoto se non lo riconosce (il subject puo' essere {@code null}). */
    List<EventLink> resolve(String subject);
}
