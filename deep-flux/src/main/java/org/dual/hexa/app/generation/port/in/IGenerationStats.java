package org.dual.hexa.app.generation.port.in;

import org.dual.hexa.app.generation.domain.GenerationStats;

/** Statistiche di sola lettura sulle generazioni (la dashboard della home). Nessuna chiamata remota. */
public interface IGenerationStats {

    /**
     * Le statistiche con una finestra di {@code windowDays} giorni (oggi compreso, nel fuso del server).
     *
     * @throws IllegalArgumentException se {@code windowDays} non e' fra 1 e {@value #MAX_WINDOW_DAYS}
     */
    GenerationStats stats(int windowDays);

    int MAX_WINDOW_DAYS = 366;
}
