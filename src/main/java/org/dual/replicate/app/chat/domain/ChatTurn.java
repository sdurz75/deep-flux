package org.dual.replicate.app.chat.domain;

/** Un turno cosi' come lo manda/vuole deep-chat: role "user" o "ai". */
public record ChatTurn(String role, String text) {
}
