package org.hexa.app.credits.domain;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Saldo Replicate inserito a mano e istante dell'inserimento (riga UNICA, id fisso): il credito mostrato e' questo saldo meno il
 * costo stimato delle generazioni create da {@code asOf} in poi.
 */
@Entity
@Table(name = "replicate_balance_anchor")
public class ReplicateBalanceAnchor {

    public static final long SINGLE_ID = 1L;

    @Id
    private Long id = SINGLE_ID;

    @Column(nullable = false, precision = 12, scale = 4)
    private BigDecimal balanceUsd;

    @Column(nullable = false)
    private Instant asOf;

    protected ReplicateBalanceAnchor() {
        // richiesto da JPA
    }

    public ReplicateBalanceAnchor(BigDecimal balanceUsd, Instant asOf) {
        this.balanceUsd = balanceUsd;
        this.asOf = asOf;
    }

    public BigDecimal getBalanceUsd() {
        return balanceUsd;
    }

    public Instant getAsOf() {
        return asOf;
    }
}
