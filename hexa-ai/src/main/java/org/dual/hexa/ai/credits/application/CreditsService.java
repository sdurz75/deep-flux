package org.dual.hexa.ai.credits.application;

import java.util.ArrayList;
import java.util.List;

import org.dual.hexa.ai.credits.domain.CreditLine;
import org.dual.hexa.ai.credits.port.in.ICreditSource;
import org.dual.hexa.ai.credits.port.in.ICredits;
import org.springframework.stereotype.Service;

/** Aggrega le righe di tutte le {@link ICreditSource} presenti (OpenRouter del core piu' quelle dell'host), nell'ordine di {@code @Order}. */
@Service
public class CreditsService implements ICredits {

    private final List<ICreditSource> sources;

    public CreditsService(List<ICreditSource> sources) {
        this.sources = sources;
    }

    @Override
    public List<CreditLine> lines() {
        List<CreditLine> lines = new ArrayList<>();
        sources.forEach(source -> lines.addAll(source.lines()));
        return lines;
    }
}
