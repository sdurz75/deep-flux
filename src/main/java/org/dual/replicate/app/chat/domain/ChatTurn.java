package org.dual.replicate.app.chat.domain;

/**
 * Un turno cosi' come lo manda/vuole deep-chat: role {@value #USER} o {@value #AI}. Il server ne aggiunge un terzo, {@value #SYSTEM}:
 * una nota dell'app per il modello (l'esito di una generazione), mai mostrata all'utente e mai salvata come turno.
 */
public record ChatTurn(String role, String text) {

    public static final String USER = "user";
    public static final String AI = "ai";
    public static final String SYSTEM = "system";
}
