package org.hexa.core.credits.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Credito residuo di un servizio, pronto per la barra in basso. {@code amountUsd} e' valorizzato solo con {@link Status#OK}.
 * {@code provider} e' una chiave libera (chiave del bundle {@code credits.label.<provider>}); {@code estimated} dice che il
 * valore e' una stima (per l'app il credito Replicate: saldo inserito a mano meno i costi stimati) e non il dato del servizio.
 */
public record CreditLine(String provider, Status status, BigDecimal amountUsd, Instant asOf, boolean estimated) {

    /** Chiave del provider OpenRouter (le altre le dichiara l'host con la propria {@code ICreditSource}). */
    public static final String OPENROUTER = "OPENROUTER";

    /** Sotto questa soglia la barra evidenzia il credito (token {@code warning}). */
    public static final BigDecimal LOW_THRESHOLD_USD = new BigDecimal("2");

    public enum Status {
        /** Importo disponibile. */
        OK,
        /** Replicate: nessun saldo ancora inserito. */
        NOT_SET,
        /** Il servizio non ha risposto (l'errore e' gia' negli eventi di sistema). */
        UNAVAILABLE
    }

    public static CreditLine ok(String provider, BigDecimal amountUsd, Instant asOf, boolean estimated) {
        return new CreditLine(provider, Status.OK, amountUsd, asOf, estimated);
    }

    public static CreditLine notSet(String provider, boolean estimated) {
        return new CreditLine(provider, Status.NOT_SET, null, null, estimated);
    }

    public static CreditLine unavailable(String provider, boolean estimated) {
        return new CreditLine(provider, Status.UNAVAILABLE, null, null, estimated);
    }

    public boolean low() {
        return status == Status.OK && amountUsd.compareTo(LOW_THRESHOLD_USD) < 0;
    }
}
