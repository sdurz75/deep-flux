package org.dual.replicate.app.chat.adapter.ai;

import org.dual.replicate.core.chat.port.in.IChatToolkit;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.dual.replicate.app.credits.domain.CreditLine;
import org.dual.replicate.app.credits.port.in.ICredits;
import org.dual.replicate.app.generation.domain.GalleryItem;
import org.dual.replicate.app.generation.domain.Generation;
import org.dual.replicate.app.generation.port.in.IGenerations;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Sola lettura dei crediti (la stessa informazione della barra in basso) e del costo stimato della conversazione corrente. Non imposta
 * mai il saldo ({@code ICredits#setReplicateBalance} e' dell'utente, dal popover del chip) e non stima il costo di una generazione PRIMA
 * che parta: Replicate non espone un prezzo anticipato e qui non si inventano cifre.
 */
@Component
@Order(70)
public class CreditsTool implements IChatToolkit {

    private final ICredits credits;
    private final IGenerations generations;
    private final ISystemEvents systemEvents;

    public CreditsTool(ICredits credits, IGenerations generations, ISystemEvents systemEvents) {
        this.credits = credits;
        this.generations = generations;
        this.systemEvents = systemEvents;
    }

    @Tool(description = "Read the remaining credit of the paid services (Replicate, which is an ESTIMATE, and OpenRouter) and the "
            + "estimated Replicate cost of the generations of this conversation so far. Use it when the user asks how much credit is "
            + "left or what a conversation cost. Read only: you cannot change the balance, and you cannot know what a generation will "
            + "cost before it runs.")
    public String getCredits(ToolContext toolContext) {
        try {
            StringBuilder out = new StringBuilder();
            List<CreditLine> lines = credits.lines();
            for (CreditLine line : lines) {
                out.append(describe(line)).append('\n');
            }
            if (lines.isEmpty()) {
                out.append("No credit information is available.\n");
            }
            Long conversationId = toolContext != null
                    && toolContext.getContext().get(LibraryTool.CONVERSATION_ID_CONTEXT_KEY) instanceof Long id ? id : null;
            if (conversationId != null) {
                out.append(conversationCost(conversationId));
            }
            return out.toString().strip();
        } catch (RuntimeException e) {
            systemEvents.record("getCredits", e);
            return "Could not read the credits (" + ISystemEvents.sanitize(e) + "). Tell the user it did not work.";
        }
    }

    private static String describe(CreditLine line) {
        String name = switch (line.provider()) {
            case REPLICATE -> "Replicate";
            case OPENROUTER -> "OpenRouter";
        };
        return switch (line.status()) {
            case OK -> name + ": " + (line.estimated() ? "~" : "") + "$" + line.amountUsd().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
                    + (line.estimated() ? " (estimate: the balance the user entered by hand minus the estimated cost of later generations)" : "")
                    + (line.low() ? " - LOW, below $" + CreditLine.LOW_THRESHOLD_USD.toPlainString() : "");
            case NOT_SET -> name + ": no balance entered yet (the user can enter it from the credit chip in the bottom bar)";
            case UNAVAILABLE -> name + ": temporarily unavailable";
        };
    }

    /** Somma dei costi stimati (solo generazioni riuscite, con un costo noto) di QUESTA conversazione: un file per riga di galleria, una generazione una volta. */
    private String conversationCost(Long conversationId) {
        Map<Long, Generation> succeeded = generations.succeededItemsForConversation(conversationId).stream()
                .map(GalleryItem::generation)
                .collect(Collectors.toMap(Generation::getId, g -> g, (a, b) -> a));
        List<BigDecimal> costs = succeeded.values().stream().map(Generation::getCostUsd).filter(java.util.Objects::nonNull).toList();
        if (costs.isEmpty()) {
            return "This conversation: no estimated cost recorded yet.";
        }
        BigDecimal total = costs.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return "This conversation: ~$" + total.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + " estimated for "
                + costs.size() + " succeeded generation(s) with a known cost.";
    }

    @Override
    public String promptSection() {
        return "deep-chat.section.credits";
    }
}
