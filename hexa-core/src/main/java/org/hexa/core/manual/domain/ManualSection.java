package org.hexa.core.manual.domain;

/**
 * Un pezzo di pagina, dal titolo di secondo livello al successivo (il primo comincia dal titolo di primo livello). E' l'unita' che si cerca e si cita:
 * {@code anchor} e' l'ancora dello stesso titolo nell'HTML. {@code markdown} e' il sorgente della sezione.
 */
public record ManualSection(String slug, String anchor, String title, String markdown) {
}
