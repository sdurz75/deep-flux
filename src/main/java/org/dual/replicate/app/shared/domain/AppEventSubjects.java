package org.dual.replicate.app.shared.domain;

import org.dual.replicate.app.shared.adapter.out.events.AppEventLinks;

/**
 * Il {@code subject} degli eventi di sistema per le entita' dell'app ({@code generation:12}, {@code conversation:5}), nello
 * stesso formato di {@code token:12} del core: a cosa si riferisce l'evento ed entra nella chiave di serie. Il link "apri"
 * nella pagina eventi lo risolve {@link AppEventLinks}.
 */
public final class AppEventSubjects {

    public static final String GENERATION = "generation";
    public static final String CONVERSATION = "conversation";

    private AppEventSubjects() {
    }

    /** Il subject piu' specifico: la generazione se c'e', altrimenti la conversazione, altrimenti {@code null}. */
    public static String of(Long generationId, Long conversationId) {
        if (generationId != null) {
            return GENERATION + ":" + generationId;
        }
        return conversationId != null ? CONVERSATION + ":" + conversationId : null;
    }
}
