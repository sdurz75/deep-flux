package org.dual.replicate.app.chat.adapter.ai;

/**
 * Un gruppo di tool dell'assistente (una classe con metodi {@code @Tool}). {@link SpringAiAssistant} riceve la lista dei toolkit
 * PRESENTI (quelli condizionali, come {@code ArchiveSearchTool}, mancano se la loro proprieta' li spegne), registra i loro tool e
 * assembla il system prompt con le sezioni che dichiarano: aggiungere un gruppo di tool non richiede di toccare l'assistente, e la
 * sezione di un gruppo assente non finisce mai nel prompt. L'ordine delle sezioni e' quello di {@code @Order} sulle classi.
 */
interface ChatToolkit {

    /** Chiave in prompts.properties della sezione del system prompt che descrive questo gruppo, o {@code null} se non ne ha una. */
    String promptSection();
}
