package org.dual.replicate.app.chat.domain;

/**
 * Un'azione PROPOSTA dall'assistente in un turno: la chat non la esegue mai, la UI mostra un bottone e solo il click dell'utente
 * chiama l'endpoint esistente (annulla, cancella) o apre la form precompilata (rigenera). Porta solo cio' che serve al bottone;
 * testi e URL stanno nei template (i18n e context path).
 *
 * @param prompt/seed/model solo per {@link Type#REGENERATE}: prompt e seed riproducibile del file, modello originale (mostrato all'utente)
 */
public record ChatAction(Type type, Long generationId, String prompt, Long seed, String model) {

    public enum Type { CANCEL, DELETE, REGENERATE }

    public static ChatAction cancel(Long generationId) {
        return new ChatAction(Type.CANCEL, generationId, null, null, null);
    }

    public static ChatAction delete(Long generationId) {
        return new ChatAction(Type.DELETE, generationId, null, null, null);
    }

    public static ChatAction regenerate(Long generationId, String prompt, Long seed, String model) {
        return new ChatAction(Type.REGENERATE, generationId, prompt, seed, model);
    }
}
