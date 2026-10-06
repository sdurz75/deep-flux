package org.dual.replicate.core.chat.domain.event;

import java.util.List;
import java.util.Locale;

/**
 * Pubblicato da {@code ChatService#reply} a fine turno (anche se l'assistente e' fallito) per gli esiti che i tool avevano gia' avviato:
 * chi ne possiede il dominio li aggancia alla conversazione e ne attende l'esito. {@code locale} e' quella della richiesta, per i thread
 * in background dove {@code LocaleContextHolder} e' vuoto.
 */
public record ChatOutcomesStartedEvent(Long conversationId, List<Long> refs, Locale locale) {
}
