package org.dual.replicate.core.kernel;

/**
 * Cio' che serve per mostrare un toast (header HX-Trigger {@code system-toast}, vedi {@code core.web.HtmxEvents}): lo implementa
 * l'esito della registrazione di un evento. Nel kernel perche' il kit web non deve dipendere dai sottosistemi (e nemmeno dagli
 * eventi, che usano il kit web nei loro adapter).
 */
public interface ToastMessage {

    /** Identifica il toast lato client per la dedupe. */
    String key();

    /** Gia' tradotto. */
    String message();

    /** Servizio temporaneamente non raggiungibile: il toast suggerisce di riprovare. */
    boolean transientFailure();

    /** {@code ERROR} o {@code WARNING}: decide lo stile del toast. */
    String severityName();
}
