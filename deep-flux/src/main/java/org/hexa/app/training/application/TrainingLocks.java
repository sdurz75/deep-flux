package org.hexa.app.training.application;

import java.util.stream.Stream;

/**
 * Lock a strisce per training (id % N), condivisi da chi modifica la RIGA di un training: l'avanzamento, l'annullamento, l'eliminazione e il completamento del
 * risultato. Una mappa che cresce non serve (le collisioni sono innocue: solo un po' di serializzazione in piu'), e uno stesso id cade sempre sulla stessa
 * striscia, quindi un poll e il completamento del risultato non scrivono mai la stessa riga insieme (la riga non ha un numero di versione).
 */
final class TrainingLocks {

    private static final Object[] LOCKS = Stream.generate(Object::new).limit(64).toArray();

    private TrainingLocks() {
    }

    static Object of(Long id) {
        return LOCKS[(int) (Math.abs(id) % LOCKS.length)];
    }
}
