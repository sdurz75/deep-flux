package org.dual.hexa.core.manual.domain;

/** Il sorgente di una pagina, come lo consegna la sorgente dei testi: gruppo e slug sono gia' ricavati dal percorso del file. */
public record ManualDocument(String group, String slug, String markdown) {
}
