package org.dual.hexa.ai.chat.port.in;

import java.util.Collection;
import java.util.Map;

import org.dual.hexa.ai.chat.domain.ChatOutcomeView;

/**
 * SPI (facoltativa) con cui chi possiede il dominio degli esiti dice alla chat come presentarli: la chat conosce solo il riferimento
 * opaco di un turno ({@code ChatMessage#getOutcomeRef}). Un riferimento non risolvibile (esito cancellato) e' semplicemente assente
 * dalla mappa e il turno resta un normale turno di testo.
 */
public interface IChatOutcomeResolver {

    Map<Long, ChatOutcomeView> resolve(Collection<Long> refs);
}
