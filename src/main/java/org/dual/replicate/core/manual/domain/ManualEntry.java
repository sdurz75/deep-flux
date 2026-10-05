package org.dual.replicate.core.manual.domain;

/**
 * Una pagina del manuale come appare nell'indice. {@code group} e' la cartella che la contiene (senza il prefisso d'ordine: {@code 01-uso} -> {@code uso}),
 * {@code slug} il nome del file senza prefisso d'ordine ed estensione (unico fra TUTTI i gruppi: la URL e' piatta, {@code /manual/{slug}}),
 * {@code title} il primo titolo di primo livello, {@code summary} il testo (senza formattazione) del primo paragrafo.
 */
public record ManualEntry(String group, String slug, String title, String summary) {
}
