package org.dual.replicate.core.credits.port.out;

import java.math.BigDecimal;

public interface IOpenRouterCreditGateway {

    /** {@code false} senza management key: il credito non si puo' leggere e non si mostra (non e' un errore). */
    boolean isConfigured();

    /** Credito residuo in USD (acquistato meno usato). Lancia {@code RemoteServiceException} se il servizio non risponde. */
    BigDecimal remaining();
}
