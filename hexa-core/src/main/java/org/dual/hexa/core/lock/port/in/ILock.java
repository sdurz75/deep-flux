package org.dual.hexa.core.lock.port.in;

import java.util.List;

/**
 * Blocco dell'app con PIN dopo un periodo di inattivita'. Senza PIN impostato il blocco e' spento e non cambia nulla ({@link #isEnabled()} falso).
 * Lo stato di sblocco di una sessione NON sta qui (e' della sessione HTTP, adapter web): questa porta sa solo se un PIN e' corretto e quanto vale il
 * timeout. Ogni verifica sbagliata rallenta i tentativi successivi (vedi {@code LockException}).
 */
public interface ILock {

    /** I timeout di inattivita' ammessi, in secondi. */
    List<Integer> ALLOWED_TIMEOUTS = List.of(60, 300, 900, 1800, 3600);

    int DEFAULT_TIMEOUT_SECONDS = 300;

    boolean isEnabled();

    /** Secondi di inattivita' dopo i quali l'app si blocca; {@link #DEFAULT_TIMEOUT_SECONDS} se il blocco e' spento. */
    int idleTimeoutSeconds();

    /**
     * Imposta il primo PIN (4-8 cifre) e attiva il blocco.
     *
     * @throws org.dual.hexa.core.lock.domain.LockException se il PIN o il timeout non sono validi o il blocco e' gia' attivo
     */
    void enable(String pin, int idleTimeoutSeconds);

    /**
     * Controlla il PIN: usare come cancello di un'azione protetta e allo sblocco.
     *
     * @throws org.dual.hexa.core.lock.domain.LockException PIN errato, o troppi tentativi (con i secondi da attendere)
     */
    void verify(String pin);

    /** Cambia il PIN; richiede quello attuale. */
    void changePin(String currentPin, String newPin);

    /** Cambia il timeout di inattivita'; richiede il PIN attuale. */
    void changeIdleTimeout(String currentPin, int idleTimeoutSeconds);

    /** Spegne il blocco; richiede il PIN attuale. */
    void disable(String currentPin);

    /** Spegne il blocco SENZA PIN: il recupero di un PIN dimenticato ({@code app.lock.reset=true} all'avvio). */
    void reset();
}
