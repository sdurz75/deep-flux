package org.dual.replicate.app.credits.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.dual.replicate.app.credits.domain.CreditLine;
import org.dual.replicate.app.credits.domain.CreditProvider;
import org.dual.replicate.app.credits.domain.CreditsException;
import org.dual.replicate.app.credits.domain.ReplicateBalanceAnchor;
import org.dual.replicate.app.credits.port.in.ICredits;
import org.dual.replicate.app.credits.port.out.IOpenRouterCreditGateway;
import org.dual.replicate.app.credits.port.out.IReplicateBalanceStore;
import org.dual.replicate.app.generation.port.in.IGenerations;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Credito residuo per la barra in basso. Replicate: STIMA = saldo inserito a mano meno {@link IGenerations#totalCostSince} dal momento
 * dell'inserimento (mai sotto zero). OpenRouter: dato del servizio, in cache {@link #TTL} (la barra si ricarica spesso, il credito
 * cambia lentamente); un errore e' registrato come evento di sistema e ricordato {@link #FAILURE_TTL} (niente chiamata a ogni
 * ricaricamento della barra durante un guasto). Senza management key la riga OpenRouter non c'e'.
 */
@Service
public class CreditsService implements ICredits {

    static final Duration TTL = Duration.ofMinutes(5);
    static final Duration FAILURE_TTL = Duration.ofMinutes(1);

    private final IReplicateBalanceStore balanceStore;
    private final IOpenRouterCreditGateway openRouter;
    private final IGenerations generations;
    private final ISystemEvents systemEvents;
    private final Messages messages;
    private final Clock clock;

    private CachedLine openRouterCache;

    @Autowired
    public CreditsService(IReplicateBalanceStore balanceStore, IOpenRouterCreditGateway openRouter, IGenerations generations,
                          ISystemEvents systemEvents, Messages messages) {
        this(balanceStore, openRouter, generations, systemEvents, messages, Clock.systemUTC());
    }

    CreditsService(IReplicateBalanceStore balanceStore, IOpenRouterCreditGateway openRouter, IGenerations generations,
                   ISystemEvents systemEvents, Messages messages, Clock clock) {
        this.balanceStore = balanceStore;
        this.openRouter = openRouter;
        this.generations = generations;
        this.systemEvents = systemEvents;
        this.messages = messages;
        this.clock = clock;
    }

    @Override
    public List<CreditLine> lines() {
        List<CreditLine> lines = new ArrayList<>();
        lines.add(replicateLine());
        if (openRouter.isConfigured()) {
            lines.add(openRouterLine());
        }
        return lines;
    }

    @Override
    public void setReplicateBalance(BigDecimal balanceUsd) {
        if (balanceUsd == null || balanceUsd.signum() < 0 || balanceUsd.compareTo(MAX_BALANCE) > 0) {
            throw new CreditsException(messages.get("credits.error.balanceInvalid"));
        }
        balanceStore.save(new ReplicateBalanceAnchor(balanceUsd.setScale(4, RoundingMode.HALF_UP), clock.instant()));
    }

    /** Tetto di sanita' (la colonna e' numeric(12,4)): un refuso da cifre in piu' e' un rifiuto, non un errore del database. */
    private static final BigDecimal MAX_BALANCE = new BigDecimal("99999999");

    private CreditLine replicateLine() {
        Optional<ReplicateBalanceAnchor> anchor = balanceStore.find();
        if (anchor.isEmpty()) {
            return CreditLine.notSet(CreditProvider.REPLICATE);
        }
        BigDecimal spent = generations.totalCostSince(anchor.get().getAsOf());
        BigDecimal remaining = anchor.get().getBalanceUsd().subtract(spent == null ? BigDecimal.ZERO : spent).max(BigDecimal.ZERO);
        return CreditLine.ok(CreditProvider.REPLICATE, remaining, anchor.get().getAsOf());
    }

    private synchronized CreditLine openRouterLine() {
        Instant now = clock.instant();
        if (openRouterCache != null && now.isBefore(openRouterCache.validUntil())) {
            return openRouterCache.line();
        }
        CreditLine line;
        Duration ttl = TTL;
        try {
            line = CreditLine.ok(CreditProvider.OPENROUTER, openRouter.remaining().max(BigDecimal.ZERO), now);
        } catch (RuntimeException e) {
            // La barra non deve mai rompere la pagina: l'errore va negli eventi di sistema (una serie, un solo toast).
            systemEvents.record("getCredits", e);
            line = CreditLine.unavailable(CreditProvider.OPENROUTER);
            ttl = FAILURE_TTL;
        }
        openRouterCache = new CachedLine(line, now.plus(ttl));
        return line;
    }

    private record CachedLine(CreditLine line, Instant validUntil) {
    }
}
