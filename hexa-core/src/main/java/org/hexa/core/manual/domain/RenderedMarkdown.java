package org.hexa.core.manual.domain;

import java.util.List;

/**
 * Esito della conversione di una pagina: l'HTML, i titoli (con le ancore), il testo del primo titolo di primo livello ({@code null} se manca) e
 * un riassunto del primo paragrafo (stringa vuota se manca).
 */
public record RenderedMarkdown(String html, List<ManualHeading> headings, String title, String summary) {
}
