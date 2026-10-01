package org.dual.replicate.core.kernel.remote;

import java.time.Duration;

/**
 * Quanti ritentativi (oltre al primo tentativo) e con quale pausa, per gli errori {@link RemoteServiceException#isTransient
 * transitori}. Sempre esplicita per chiamata: le operazioni NON idempotenti (o che costano) usano {@link #NONE}.
 */
public record RetryPolicy(int retries, Duration backoff) {

    /** Nessun ritentativo: creare una prediction a pagamento, un turno LLM con tool. */
    public static final RetryPolicy NONE = new RetryPolicy(0, Duration.ZERO);

    /** Default per le chiamate idempotenti: 2 ritentativi a 500 ms. */
    public static final RetryPolicy DEFAULT = new RetryPolicy(2, Duration.ofMillis(500));

    public static RetryPolicy of(int retries, Duration backoff) {
        return new RetryPolicy(retries, backoff);
    }
}
