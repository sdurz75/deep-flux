package org.dual.hexa.pwa.shell.domain;

/** Identificatore del modulo e chiavi della sua configurazione (dichiarate da {@code PwaConfigModule}, lette da {@code PwaService}). */
public final class PwaSettings {

    public static final String ID = "pwa";
    public static final String THEME_COLOR = "theme-color";
    public static final String THEME_COLOR_DARK = "theme-color-dark";
    public static final String DISPLAY = "display";

    private PwaSettings() {
    }
}
