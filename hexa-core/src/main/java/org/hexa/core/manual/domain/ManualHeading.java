package org.hexa.core.manual.domain;

/** Un titolo di una pagina: {@code id} e' l'ancora (vedi {@link HeadingSlugs}), {@code line} la riga (da 1) del sorgente Markdown. */
public record ManualHeading(int level, String id, String text, int line) {
}
