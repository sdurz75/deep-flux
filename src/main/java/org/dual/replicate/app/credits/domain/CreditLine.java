package org.dual.replicate.app.credits.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Credito residuo di un servizio, pronto per la barra in basso. {@code amountUsd} e' valorizzato solo con {@link Status#OK}.
 * Il credito Replicate e' sempre una STIMA ({@link #estimated()}: saldo inserito a mano meno i costi stimati delle generazioni
 * successive, Replicate non espone il saldo); quello di OpenRouter e' il dato del servizio.
 */
public record CreditLine(CreditProvider provider, Status status, BigDecimal amountUsd, Instant asOf) {

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

    public static CreditLine ok(CreditProvider provider, BigDecimal amountUsd, Instant asOf) {
        return new CreditLine(provider, Status.OK, amountUsd, asOf);
    }

    public static CreditLine notSet(CreditProvider provider) {
        return new CreditLine(provider, Status.NOT_SET, null, null);
    }

    public static CreditLine unavailable(CreditProvider provider) {
        return new CreditLine(provider, Status.UNAVAILABLE, null, null);
    }

    public boolean estimated() {
        return provider == CreditProvider.REPLICATE;
    }

    public boolean low() {
        return status == Status.OK && amountUsd.compareTo(LOW_THRESHOLD_USD) < 0;
    }
}
