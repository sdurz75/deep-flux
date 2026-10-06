package org.dual.replicate.app.credits.port.in;

import java.math.BigDecimal;

/** Saldo Replicate inserito a mano (Replicate non lo espone): da qui in poi la barra sottrae i costi stimati delle generazioni. */
public interface IReplicateBalance {

    /** Imposta il saldo ORA. Rifiuta un importo assente o negativo. */
    void setReplicateBalance(BigDecimal balanceUsd);
}
