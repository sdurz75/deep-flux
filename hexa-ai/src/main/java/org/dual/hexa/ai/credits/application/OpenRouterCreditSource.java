package org.dual.hexa.ai.credits.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.dual.hexa.ai.credits.domain.CreditLine;
import org.dual.hexa.ai.credits.port.in.ICreditSource;
import org.dual.hexa.ai.credits.port.out.IOpenRouterCreditGateway;
import org.dual.hexa.core.events.port.in.ISystemEvents;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * Credito OpenRouter: dato del servizio, in cache {@link #TTL} (la barra si ricarica spesso, il credito cambia lentamente); un errore e'
 * registrato come evento di sistema e ricordato {@link #FAILURE_TTL} (niente chiamata a ogni ricaricamento della barra durante un
 * guasto). Senza management key la riga non c'e'.
 */
@Service
@Order(100)
public class OpenRouterCreditSource implements ICreditSource {

    static final Duration TTL = Duration.ofMinutes(5);
    static final Duration FAILURE_TTL = Duration.ofMinutes(1);

    private final IOpenRouterCreditGateway openRouter;
    private final ISystemEvents systemEvents;
    private final Clock clock;

    private CachedLine cache;

    @Autowired
    public OpenRouterCreditSource(IOpenRouterCreditGateway openRouter, ISystemEvents systemEvents) {
        this(openRouter, systemEvents, Clock.systemUTC());
    }

    OpenRouterCreditSource(IOpenRouterCreditGateway openRouter, ISystemEvents systemEvents, Clock clock) {
        this.openRouter = openRouter;
        this.systemEvents = systemEvents;
        this.clock = clock;
    }

    @Override
    public synchronized List<CreditLine> lines() {
        if (!openRouter.isConfigured()) {
            return List.of();
        }
        Instant now = clock.instant();
        if (cache != null && now.isBefore(cache.validUntil())) {
            return List.of(cache.line());
        }
        CreditLine line;
        Duration ttl = TTL;
        try {
            line = CreditLine.ok(CreditLine.OPENROUTER, openRouter.remaining().max(BigDecimal.ZERO), now, false);
        } catch (RuntimeException e) {
            // La barra non deve mai rompere la pagina: l'errore va negli eventi di sistema (una serie, un solo toast).
            systemEvents.record("getCredits", e);
            line = CreditLine.unavailable(CreditLine.OPENROUTER, false);
            ttl = FAILURE_TTL;
        }
        cache = new CachedLine(line, now.plus(ttl));
        return List.of(line);
    }

    private record CachedLine(CreditLine line, Instant validUntil) {
    }
}
