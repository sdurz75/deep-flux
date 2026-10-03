package org.dual.replicate.app.credits.port.in;

import java.math.BigDecimal;
import java.util.List;

import org.dual.replicate.app.credits.domain.CreditLine;

/**
 * Credito residuo sui servizi a pagamento (Replicate, OpenRouter) per la barra in basso. Non fa mai fallire il chiamante: un servizio
 * che non risponde diventa una riga {@link CreditLine.Status#UNAVAILABLE} (e un evento di sistema).
 */
public interface ICredits {

    /** Righe da mostrare, nell'ordine Replicate, OpenRouter. OpenRouter manca se non e' configurata la management key. */
    List<CreditLine> lines();

    /** Imposta il saldo Replicate ORA (da qui in poi si sottraggono i costi). Rifiuta un importo assente o negativo. */
    void setReplicateBalance(BigDecimal balanceUsd);
}
