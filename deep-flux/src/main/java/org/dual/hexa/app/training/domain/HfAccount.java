package org.dual.hexa.app.training.domain;

/**
 * L'account HuggingFace a cui appartiene un token: {@code username} e' dove vivra' il repo ({@code username/nome}); {@code role} e' il tipo di token
 * ({@code read}, {@code write}, {@code fineGrained}...) come lo riporta il servizio.
 */
public record HfAccount(String username, String role) {

    /** Un token di sola lettura non puo' creare il repo ne' caricarci i pesi: si rifiuta PRIMA di spendere. Gli altri tipi si lasciano passare (un fine-grained puo' avere o no il permesso). */
    public boolean isReadOnly() {
        return "read".equalsIgnoreCase(role);
    }
}
