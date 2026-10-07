package org.hexa.core.events.domain;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Gravita' di un {@link SystemEvent}: ERROR = guasto (le chiamate remote e gli errori interni di sempre), WARNING = qualcosa
 * che richiede attenzione prima che diventi un guasto (es. un token in scadenza). L'ordine conta ({@link #isAtLeast}):
 * la campanella della toolbar si accende per gli eventi "almeno WARNING" non ancora visualizzati, cosi' un eventuale
 * livello futuro piu' basso (es. INFO) non la accenderebbe.
 */
public enum SystemEventSeverity {
    WARNING(1),
    ERROR(2);

    private final int rank;

    SystemEventSeverity(int rank) {
        this.rank = rank;
    }

    public boolean isAtLeast(SystemEventSeverity minimum) {
        return rank >= minimum.rank;
    }

    /** Tutte le severita' pari o superiori a {@code minimum} (per le query "almeno warning"). */
    public static Set<SystemEventSeverity> atLeast(SystemEventSeverity minimum) {
        return Arrays.stream(values()).filter(s -> s.isAtLeast(minimum)).collect(Collectors.toSet());
    }
}
