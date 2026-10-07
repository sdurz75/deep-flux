package org.dual.hexa.app.training.domain;

import java.util.List;

/**
 * Cosa dice il pannello "Avvia" di una bozza prima di spendere: i {@code blockers} impediscono il lancio, i {@code warnings} no (le didascalie che non nominano
 * la trigger word, poche immagini). I testi sono gia' tradotti.
 */
public record LaunchCheck(List<String> blockers, List<String> warnings) {

    public LaunchCheck {
        blockers = List.copyOf(blockers);
        warnings = List.copyOf(warnings);
    }

    public boolean isLaunchable() {
        return blockers.isEmpty();
    }
}
