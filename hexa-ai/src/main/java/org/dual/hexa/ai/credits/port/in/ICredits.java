package org.dual.hexa.ai.credits.port.in;

import java.util.List;

import org.dual.hexa.ai.credits.domain.CreditLine;

/**
 * Credito residuo sui servizi a pagamento per la barra in basso: le righe di OpenRouter (core) piu' quelle che l'host contribuisce con
 * le proprie {@link ICreditSource}. Non fa mai fallire il chiamante: un servizio che non risponde diventa una riga
 * {@link CreditLine.Status#UNAVAILABLE} (e un evento di sistema).
 */
public interface ICredits {

    /** Righe da mostrare, nell'ordine delle sorgenti ({@code @Order}). */
    List<CreditLine> lines();
}
