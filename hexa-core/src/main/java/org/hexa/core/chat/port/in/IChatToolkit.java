package org.hexa.core.chat.port.in;

import java.util.Map;

import org.hexa.core.chat.domain.ChatTurnResult;

/**
 * Un gruppo di tool dell'assistente (una classe con metodi {@code @Tool}). {@code SpringAiAssistant} riceve la lista dei toolkit
 * PRESENTI (quelli condizionali, come {@code ArchiveSearchTool}, mancano se la loro proprieta' li spegne), registra i loro tool e
 * assembla il system prompt con le sezioni che dichiarano: aggiungere un gruppo di tool non richiede di toccare l'assistente, e la
 * sezione di un gruppo assente non finisce mai nel prompt. L'ordine delle sezioni e' quello di {@code @Order} sulle classi.
 *
 * <p>Uno stato per turno (un canale di uscita come {@code GenerationResultHolder}) lo crea il toolkit in {@link #beginTurn} dentro il
 * {@code ToolContext} e lo raccoglie in {@link #endTurn}: il bean e' un singleton e il ToolContext e' solo caller -> tool.
 */
public interface IChatToolkit {

    /** Chiave in prompts.properties della sezione del system prompt che descrive questo gruppo, o {@code null} se non ne ha una. */
    String promptSection();

    /** Prima della chiamata al modello: il toolkit mette nel {@code ToolContext} lo stato del turno che gli serve. */
    default void beginTurn(Map<String, Object> toolContext) {
    }

    /** Dopo la chiamata (riuscita o no): il toolkit riporta in {@code result} cio' che i suoi tool hanno prodotto. Puo' essere chiamato una sola volta per turno. */
    default void endTurn(Map<String, Object> toolContext, ChatTurnResult result) {
    }
}
