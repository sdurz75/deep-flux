package org.dual.replicate.app.shared.domain;


/**
 * Il {@code subject} degli eventi di sistema per le entita' dell'app ({@code generation:12}, {@code conversation:5}), nello
 * stesso formato di {@code token:12} del core: a cosa si riferisce l'evento ed entra nella chiave di serie. Il link "apri"
 * nella pagina eventi lo risolve {@code AppEventLinks}.
 */
public final class AppEventSubjects {

    public static final String GENERATION = "generation";
    public static final String CONVERSATION = "conversation";
    public static final String TRAINING = "training";

    private AppEventSubjects() {
    }

    /** Il subject degli eventi di un training ({@code training:7}). */
    public static String ofTraining(Long trainingId) {
        return trainingId == null ? null : TRAINING + ":" + trainingId;
    }

    /** Il subject piu' specifico: la generazione se c'e', altrimenti la conversazione, altrimenti {@code null}. */
    public static String of(Long generationId, Long conversationId) {
        if (generationId != null) {
            return GENERATION + ":" + generationId;
        }
        return conversationId != null ? CONVERSATION + ":" + conversationId : null;
    }
}
