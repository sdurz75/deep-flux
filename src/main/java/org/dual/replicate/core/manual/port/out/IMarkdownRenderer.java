package org.dual.replicate.core.manual.port.out;

import org.dual.replicate.core.manual.domain.RenderedMarkdown;

/** Da Markdown a HTML. L'HTML grezzo nel sorgente non passa (si vede come testo) e i link sono riscritti secondo {@code ManualLinks}. */
public interface IMarkdownRenderer {

    /** {@code linkPrefix} si antepone ai link verso l'app e verso le altre pagine del manuale (il context path della richiesta; vuoto = nessuno). */
    RenderedMarkdown render(String markdown, String linkPrefix);
}
