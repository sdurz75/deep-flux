package org.dual.replicate.app.credits.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

import org.dual.replicate.app.credits.domain.CreditsException;
import org.dual.replicate.app.credits.domain.ReplicateBalanceAnchor;
import org.dual.replicate.app.credits.port.in.IReplicateBalance;
import org.dual.replicate.app.credits.port.out.IReplicateBalanceStore;
import org.dual.replicate.app.generation.port.in.IGenerations;
import org.dual.replicate.core.credits.domain.CreditLine;
import org.dual.replicate.core.credits.port.in.ICreditSource;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * Credito Replicate: STIMA = saldo inserito a mano meno {@link IGenerations#totalCostSince} dal momento dell'inserimento (mai sotto
 * zero). Sorgente di riga dell'app per {@code ICreditSource} (prima di OpenRouter).
 */
@Service
@Order(10)
public class ReplicateCreditsService implements ICreditSource, IReplicateBalance {

    public static final String REPLICATE = "REPLICATE";

    /** Tetto di sanita' (la colonna e' numeric(12,4)): un refuso da cifre in piu' e' un rifiuto, non un errore del database. */
    private static final BigDecimal MAX_BALANCE = new BigDecimal("99999999");

    private final IReplicateBalanceStore balanceStore;
    private final IGenerations generations;
    private final Messages messages;
    private final Clock clock;

    @Autowired
    public ReplicateCreditsService(IReplicateBalanceStore balanceStore, IGenerations generations, Messages messages) {
        this(balanceStore, generations, messages, Clock.systemUTC());
    }

    ReplicateCreditsService(IReplicateBalanceStore balanceStore, IGenerations generations, Messages messages, Clock clock) {
        this.balanceStore = balanceStore;
        this.generations = generations;
        this.messages = messages;
        this.clock = clock;
    }

    @Override
    public List<CreditLine> lines() {
        Optional<ReplicateBalanceAnchor> anchor = balanceStore.find();
        if (anchor.isEmpty()) {
            return List.of(CreditLine.notSet(REPLICATE, true));
        }
        BigDecimal spent = generations.totalCostSince(anchor.get().getAsOf());
        BigDecimal remaining = anchor.get().getBalanceUsd().subtract(spent == null ? BigDecimal.ZERO : spent).max(BigDecimal.ZERO);
        return List.of(CreditLine.ok(REPLICATE, remaining, anchor.get().getAsOf(), true));
    }

    @Override
    public void setReplicateBalance(BigDecimal balanceUsd) {
        if (balanceUsd == null || balanceUsd.signum() < 0 || balanceUsd.compareTo(MAX_BALANCE) > 0) {
            throw new CreditsException(messages.get("credits.error.balanceInvalid"));
        }
        balanceStore.save(new ReplicateBalanceAnchor(balanceUsd.setScale(4, RoundingMode.HALF_UP), clock.instant()));
    }
}
