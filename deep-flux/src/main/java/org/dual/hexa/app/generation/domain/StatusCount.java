package org.dual.hexa.app.generation.domain;

/** Righe per stato: risultato di una query di raggruppamento (costruttore JPQL). */
public record StatusCount(GenerationStatus status, long count) {
}
