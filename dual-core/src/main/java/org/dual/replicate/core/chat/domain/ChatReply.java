package org.dual.replicate.core.chat.domain;

import java.util.List;
import java.util.Map;

/**
 * Non porta un'eventuale immagine: il tool ritorna subito, prima che un esito avviato in questo turno sia pronto: arriva sempre in un
 * secondo momento via push SSE, mai nella risposta sincrona. {@code startedOutcomeRefs} sono i riferimenti opachi degli esiti avviati
 * (vedi {@code ChatOutcomesStartedEvent}); {@code extras} sono le parti della risposta JSON che i toolkit aggiungono al testo, per
 * chiave (oggi {@code generationIds} e {@code actions}: bottoni di conferma, mai eseguiti dal turno e non persistiti, dopo un reload
 * l'assistente li ripropone se serve).
 */
public record ChatReply(String text, List<Long> startedOutcomeRefs, Map<String, Object> extras) {

    public ChatReply(String text, List<Long> startedOutcomeRefs) {
        this(text, startedOutcomeRefs, Map.of());
    }
}
