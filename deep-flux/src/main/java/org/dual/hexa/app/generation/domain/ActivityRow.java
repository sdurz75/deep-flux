package org.dual.hexa.app.generation.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** Una generazione ridotta a cio' che serve alle statistiche (istante, esito, costo stimato; {@code costUsd} puo' essere {@code null}). */
public record ActivityRow(Instant createdAt, GenerationStatus status, BigDecimal costUsd) {
}
