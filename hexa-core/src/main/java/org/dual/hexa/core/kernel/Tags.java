package org.dual.hexa.core.kernel;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Normalizzazione unica dei tag UTENTE (generazioni, file, conversazioni): lo stesso testo normalizzato si salva, si indicizza e si
 * confronta nel filtro (il match nell'indice e' esatto). Non sono le tag d'indice (vocabolario dopo {@code #tags:}) ne' i tag AI
 * delle immagini importate.
 */
public final class Tags {

    public static final int MAX_LENGTH = 40;
    public static final int MAX_PER_ENTITY = 20;

    private Tags() {
    }

    /** Trim, minuscolo, spazi interni collassati, virgole, virgolette e backslash tolti (finiscono in un filtro jsonpath), tetto di {@link #MAX_LENGTH}; stringa vuota se non resta nulla. */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.replace(',', ' ').replace('"', ' ').replace('\\', ' ').strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return cleaned.length() > MAX_LENGTH ? cleaned.substring(0, MAX_LENGTH).strip() : cleaned;
    }

    /** Un elenco separato da virgole, normalizzato e senza doppioni ne' voci vuote. */
    public static Set<String> parse(String csv) {
        Set<String> tags = new LinkedHashSet<>();
        if (csv != null) {
            for (String part : csv.split(",")) {
                String tag = normalize(part);
                if (!tag.isEmpty()) {
                    tags.add(tag);
                }
            }
        }
        return tags;
    }
}
