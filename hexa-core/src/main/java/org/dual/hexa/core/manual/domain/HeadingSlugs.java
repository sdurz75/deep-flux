package org.dual.hexa.core.manual.domain;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * L'UNICA funzione che trasforma il testo di un titolo nell'ancora ({@code id}): la usa il renderer per gli {@code id} dell'HTML e la usa chi
 * cita una sezione (il bot, i link fra pagine), quindi non possono divergere. Minuscolo, senza diacritici (le ancore sono ASCII), ogni serie di
 * caratteri non alfanumerici diventa un trattino. Una istanza per pagina: i titoli ripetuti prendono {@code -1}, {@code -2}...
 */
public final class HeadingSlugs {

    private final Set<String> used = new HashSet<>();

    /** L'ancora del prossimo titolo della pagina (unica nella pagina). */
    public String next(String headingText) {
        String base = slug(headingText);
        if (base.isEmpty()) {
            base = "sezione";
        }
        String candidate = base;
        int n = 1;
        while (!used.add(candidate)) {
            candidate = base + "-" + n++;
        }
        return candidate;
    }

    /** La forma base di un titolo, senza gestire i duplicati. */
    public static String slug(String text) {
        String folded = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return folded.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }
}
