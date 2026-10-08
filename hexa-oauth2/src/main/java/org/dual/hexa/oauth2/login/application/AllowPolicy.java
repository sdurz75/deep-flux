package org.dual.hexa.oauth2.login.application;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Le regole di confronto della lista degli ammessi, pure e senza stato (si provano senza Spring). L'email si confronta normalizzata (tolti gli spazi,
 * minuscolo); un dominio vale solo se UGUALE alla parte dopo l'ultima chiocciola: mai {@code endsWith}, o {@code evilexample.com} entrerebbe con
 * {@code example.com}.
 */
final class AllowPolicy {

    private static final Pattern EMAIL = Pattern.compile("[^@\\s,;\"'<>\\\\]+@[a-z0-9]([a-z0-9.-]*[a-z0-9])?\\.[a-z]{2,}");
    private static final Pattern DOMAIN = Pattern.compile("[a-z0-9]([a-z0-9.-]*[a-z0-9])?\\.[a-z]{2,}");

    private AllowPolicy() {
    }

    static String normalizeEmail(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }

    static String normalizeDomain(String domain) {
        String clean = domain == null ? "" : domain.strip().toLowerCase(Locale.ROOT);
        return clean.startsWith("@") ? clean.substring(1) : clean;
    }

    static boolean isEmail(String normalized) {
        return EMAIL.matcher(normalized).matches();
    }

    static boolean isDomain(String normalized) {
        return DOMAIN.matcher(normalized).matches();
    }

    /** La parte dopo l'ultima chiocciola di un'email normalizzata, vuota se non c'e'. */
    static String domainOf(String normalizedEmail) {
        int at = normalizedEmail.lastIndexOf('@');
        return at < 0 ? "" : normalizedEmail.substring(at + 1);
    }
}
