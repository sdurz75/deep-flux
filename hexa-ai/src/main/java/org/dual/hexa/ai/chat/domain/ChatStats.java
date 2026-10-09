package org.dual.hexa.ai.chat.domain;

import java.time.Instant;

/** Numeri di sintesi della chat: conversazioni, turni totali e ultima attivita' ({@code null} se non ce n'e' ancora). */
public record ChatStats(long conversations, long messages, Instant lastActivity) {
}
