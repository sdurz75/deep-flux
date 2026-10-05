package org.dual.replicate.app;

/**
 * Ordine dei listener di {@code ApplicationReadyEvent} dell'app che dipendono l'uno dall'altro (valori di {@code @Order}). Sta nel
 * package radice perche' e' condiviso fra feature: prima il recupero delle generazioni, poi quello della chat (che scrive i turni
 * mancanti delle generazioni gia' fatte avanzare), poi quello dei lavori di addestramento (didascalie in sospeso).
 */
public final class AppStartupOrder {

    public static final int GENERATION_RECOVERY = 1;
    public static final int CHAT_RECOVERY = 2;
    public static final int TRAINING_RECOVERY = 3;

    private AppStartupOrder() {
    }
}
