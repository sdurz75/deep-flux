package org.dual.hexa.ai.chat.domain;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cio' che i tool di un turno hanno prodotto oltre al testo, raccolto dai toolkit a fine turno ({@code IChatToolkit#endTurn}): i
 * riferimenti opachi degli esiti che avvieranno (una generazione) e le parti extra della risposta JSON ({@code extras}, per chiave).
 * Idempotente: raccogliere due volte lo stesso stato non duplica nulla.
 */
public final class ChatTurnResult {

    private final Set<Long> startedOutcomeRefs = new LinkedHashSet<>();
    private final Map<String, Object> extras = new LinkedHashMap<>();

    public void startedOutcome(Long ref) {
        startedOutcomeRefs.add(ref);
    }

    public void extra(String key, Object value) {
        extras.put(key, value);
    }

    public List<Long> startedOutcomeRefs() {
        return List.copyOf(startedOutcomeRefs);
    }

    public Map<String, Object> extras() {
        return Map.copyOf(extras);
    }
}
