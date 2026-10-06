package org.dual.replicate.app.training.domain.event;

/**
 * L'utente ha chiesto di caricare a mano i pesi di un training su HuggingFace (il push del trainer non ha funzionato). {@code tokenId} e' il token scelto in
 * /tokens: l'ID, mai il segreto. Il lavoro (scaricare e caricare qualche centinaio di MB) lo fa un thread a parte.
 */
public record HfUploadRequestedEvent(Long trainingId, Long tokenId) {
}
