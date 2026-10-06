package org.dual.replicate.app.shared.domain;

import org.dual.replicate.core.chat.domain.ChatEventSubjects;

/**
 * Il {@code subject} degli eventi di sistema per le entita' dell'app ({@code generation:12}, {@code conversation:5}, {@code training:7}, {@code trainingDataset:3}), nello
 * stesso formato di {@code token:12} del core: a cosa si riferisce l'evento ed entra nella chiave di serie. Il link "apri"
 * nella pagina eventi lo risolve {@code AppEventLinks}.
 */
public final class AppEventSubjects {

    public static final String GENERATION = "generation";
    public static final String CONVERSATION = ChatEventSubjects.CONVERSATION;
    public static final String TRAINING = "training";
    public static final String TRAINING_DATASET = "trainingDataset";

    private AppEventSubjects() {
    }

    /** Il subject degli eventi di un training ({@code training:7}). */
    public static String ofTraining(Long trainingId) {
        return trainingId == null ? null : TRAINING + ":" + trainingId;
    }

    /** Il subject degli eventi di un dataset di addestramento, bozza o snapshot ({@code trainingDataset:3}): didascalie, caricamenti, file. */
    public static String ofTrainingDataset(Long datasetId) {
        return datasetId == null ? null : TRAINING_DATASET + ":" + datasetId;
    }

    /** Il subject piu' specifico: la generazione se c'e', altrimenti la conversazione, altrimenti {@code null}. */
    public static String of(Long generationId, Long conversationId) {
        if (generationId != null) {
            return GENERATION + ":" + generationId;
        }
        return ChatEventSubjects.ofConversation(conversationId);
    }
}
