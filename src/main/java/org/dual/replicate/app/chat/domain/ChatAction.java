package org.dual.replicate.app.chat.domain;

/**
 * Un'azione PROPOSTA dall'assistente in un turno: la chat non la esegue mai, la UI mostra un bottone e solo il click dell'utente
 * chiama l'endpoint esistente (annulla, cancella) o apre la form precompilata (rigenera). Porta solo cio' che serve al bottone;
 * testi e URL stanno nei template (i18n e context path).
 *
 * @param filename/model solo per {@link Type#REGENERATE}: il file da riprodurre (la form si apre con la configurazione completa della generazione,
 *                       seed del file incluso, ricostruita dal server) e il modello originale (mostrato all'utente)
 */
public record ChatAction(Type type, Long generationId, String filename, String model) {

    public enum Type { CANCEL, DELETE, REGENERATE }

    public static ChatAction cancel(Long generationId) {
        return new ChatAction(Type.CANCEL, generationId, null, null);
    }

    public static ChatAction delete(Long generationId) {
        return new ChatAction(Type.DELETE, generationId, null, null);
    }

    public static ChatAction regenerate(Long generationId, String filename, String model) {
        return new ChatAction(Type.REGENERATE, generationId, filename, model);
    }
}
