package org.dual.hexa.app.generation.domain;

import java.math.BigDecimal;

/** Quanto e' stato usato un modello nel periodo: generazioni lanciate e costo stimato complessivo. */
public record ModelUsage(String model, long count, BigDecimal costUsd) {
}
