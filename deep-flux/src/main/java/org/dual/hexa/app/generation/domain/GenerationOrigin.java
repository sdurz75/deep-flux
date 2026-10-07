package org.dual.hexa.app.generation.domain;

/** Da dove viene una riga dell'archivio: prodotta da una prediction ({@link #GENERATED}) o ricevuta dall'esterno ({@link #IMPORTED}). */
public enum GenerationOrigin {
    GENERATED,
    IMPORTED
}
