package org.dual.hexa.app.generation.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Un giorno (nel fuso del server) della serie di attivita': generazioni riuscite, fallite e costo stimato. */
public record DailyActivity(LocalDate day, long succeeded, long failed, BigDecimal costUsd) {

    public long total() {
        return succeeded + failed;
    }
}
