package org.dual.hexa.core.lock.port.in;

import java.util.List;

/**
 * SPI per le librerie opzionali: path che il cancello del blocco lascia passare anche a sessione bloccata (es. {@code hexa-pwa}: {@code /sw.js},
 * {@code /offline}, il manifest e le icone, che il browser richiede senza sessione). Un path che finisce con {@code /} vale come prefisso, gli altri
 * sono esatti; sono relativi al context path. Un host aggiunge i suoi con {@code app.lock.exempt-paths} (elenco separato da virgole).
 */
public interface ILockExemptPaths {

    List<String> paths();
}
