package org.dual.hexa.app.chat.domain;

/**
 * Un'azione PROPOSTA dall'assistente in un turno: la chat non la esegue mai, la UI mostra un bottone e solo il click dell'utente
 * chiama l'endpoint esistente (annulla, cancella generazione o file) o apre la form precompilata (rigenera, anima, usa come sorgente).
 * Porta solo cio' che serve al bottone; testi e URL stanno nei template (i18n e context path).
 *
 * @param filename il file a cui si riferisce l'azione (tutti i tipi tranne CANCEL e DELETE): per {@link Type#REGENERATE} quello da riprodurre
 *                 (la form si apre con la configurazione completa della generazione, seed del file incluso, ricostruita dal server)
 * @param model solo per {@link Type#REGENERATE}: il modello originale (mostrato all'utente)
 */
public record ChatAction(Type type, Long generationId, String filename, String model) {

    public enum Type { CANCEL, DELETE, REGENERATE, ANIMATE, USE_AS_SOURCE, DELETE_FILE }

    public static ChatAction cancel(Long generationId) {
        return new ChatAction(Type.CANCEL, generationId, null, null);
    }

    public static ChatAction delete(Long generationId) {
        return new ChatAction(Type.DELETE, generationId, null, null);
    }

    public static ChatAction regenerate(Long generationId, String filename, String model) {
        return new ChatAction(Type.REGENERATE, generationId, filename, model);
    }

    /** Apre la form video con QUESTA immagine come sorgente. */
    public static ChatAction animate(Long generationId, String filename) {
        return new ChatAction(Type.ANIMATE, generationId, filename, null);
    }

    /** Apre la form delle immagini con QUESTA immagine come sorgente (img2img, kontext, inpainting). */
    public static ChatAction useAsSource(Long generationId, String filename) {
        return new ChatAction(Type.USE_AS_SOURCE, generationId, filename, null);
    }

    /** Elimina UN file (l'ultimo elimina a cascata la generazione). */
    public static ChatAction deleteFile(Long generationId, String filename) {
        return new ChatAction(Type.DELETE_FILE, generationId, filename, null);
    }
}
