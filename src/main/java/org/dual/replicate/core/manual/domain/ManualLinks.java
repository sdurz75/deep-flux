package org.dual.replicate.core.manual.domain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Come si leggono i link scritti nei {@code .md}: l'unica regola, condivisa dal renderer (che li riscrive) e da chi li controlla.
 * Fra pagine si scrive il nome VERO del file ({@code ../01-uso/03-genera-immagini.md#parametri}, che funziona anche su GitHub e negli IDE);
 * verso l'app un path radice ({@code /gallery}); {@code #ancora} resta nella pagina; il resto e' esterno.
 */
public final class ManualLinks {

    public enum Kind { ANCHOR, PAGE, APP, EXTERNAL, OTHER }

    /** {@code slug} e {@code anchor} (senza {@code #}, vuota se manca) valgono per {@code PAGE}; {@code anchor} anche per {@code ANCHOR}; {@code path} per {@code APP}. */
    public record Target(Kind kind, String slug, String anchor, String path) {
    }

    private static final Pattern SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:");
    private static final Pattern PAGE = Pattern.compile("^(?:[\\w.\\-]+/)*([\\w\\-]+)\\.md(?:#([\\w\\-]*))?$");
    private static final Pattern ORDER_PREFIX = Pattern.compile("^\\d+-");

    private ManualLinks() {
    }

    /** Il nome di un file o di una cartella senza il prefisso d'ordine ({@code 03-genera-immagini} -> {@code genera-immagini}). */
    public static String withoutOrder(String name) {
        return ORDER_PREFIX.matcher(name).replaceFirst("");
    }

    public static Target classify(String href) {
        if (href == null || href.isBlank()) {
            return new Target(Kind.OTHER, null, null, null);
        }
        if (href.startsWith("#")) {
            return new Target(Kind.ANCHOR, null, href.substring(1), null);
        }
        if (SCHEME.matcher(href).find() || href.startsWith("//")) {
            return new Target(Kind.EXTERNAL, null, null, null);
        }
        if (href.startsWith("/")) {
            return new Target(Kind.APP, null, null, href);
        }
        Matcher page = PAGE.matcher(href);
        if (page.matches()) {
            return new Target(Kind.PAGE, withoutOrder(page.group(1)), page.group(2) == null ? "" : page.group(2), null);
        }
        return new Target(Kind.OTHER, null, null, null);
    }
}
