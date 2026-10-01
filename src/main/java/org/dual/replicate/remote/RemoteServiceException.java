package org.dual.replicate.remote;

import org.dual.replicate.domain.SystemEventSource;

/**
 * Radice di ogni errore legato a un servizio esterno (Replicate, OpenRouter, SearXNG, storage): un solo tipo che porta
 * con se' DA DOVE viene ({@link #source()}, cosi' {@code SystemEventService} non ha bisogno che gliela si passi a mano) e
 * CHE COSA farne ({@link #kind()}). Vedi "Convenzione: errori delle chiamate remote" in CLAUDE.md e {@link RemoteCaller}.
 */
public class RemoteServiceException extends RuntimeException {

    /** Che cosa ha senso fare di un errore. */
    public enum Kind {
        /** Rete, timeout, 408/429/5xx: ha senso riprovare. */
        TRANSIENT,
        /** 4xx (token errato, id inesistente), 507, risposta illeggibile: riprovare non serve. */
        PERMANENT,
        /** Token/credenziali/chiave mancanti: permanente, non e' colpa dell'input dell'utente e va notificata. */
        CONFIGURATION,
        /**
         * Rifiuto applicativo ATTESO (validazione, troppe prediction, rifiuto del modello): non e' un guasto, quindi
         * niente registro ne' toast, solo il messaggio nel form.
         */
        REJECTED
    }

    private final SystemEventSource source;
    private final Kind kind;

    public RemoteServiceException(SystemEventSource source, Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.source = source;
        this.kind = kind;
    }

    public SystemEventSource source() {
        return source;
    }

    public Kind kind() {
        return kind;
    }

    /** {@code true} se ha senso riprovare (vedi {@link Kind#TRANSIENT}). */
    public boolean isTransient() {
        return kind == Kind.TRANSIENT;
    }

    /** {@code true} se va registrato e notificato (tutto tranne un rifiuto applicativo atteso). */
    public boolean isReportable() {
        return kind != Kind.REJECTED;
    }
}
