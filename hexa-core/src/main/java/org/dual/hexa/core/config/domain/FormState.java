package org.dual.hexa.core.config.domain;

import java.util.List;
import java.util.Map;

/**
 * Cio' che la pagina Impostazioni mostra di un modulo: {@code values} (testi dei campi semplici; per una {@code LIST} il contenuto della textarea, una voce
 * per riga), {@code secretHints} (ultimi caratteri dei segreti impostati, mai il valore), {@code environment} (voci di una lista additiva che arrivano da
 * property o ambiente, in sola lettura) e {@code rows} (le righe delle {@code COLLECTION}).
 */
public record FormState(Map<String, String> values, Map<String, String> secretHints, Map<String, List<String>> environment, Map<String, List<Row>> rows) {

    /** Una riga di una {@code COLLECTION} nel form; {@code removed}: l'utente l'aveva marcata da togliere (dopo un rifiuto resta marcata). */
    public record Row(String id, Map<String, String> values, Map<String, String> secretHints, boolean removed) {
    }
}
