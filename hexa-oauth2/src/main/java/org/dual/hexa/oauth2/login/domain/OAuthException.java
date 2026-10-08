package org.dual.hexa.oauth2.login.domain;

import org.dual.hexa.core.kernel.remote.RemoteServiceException;

/**
 * Errore di hexa-oauth2. {@code REJECTED} per un rifiuto atteso (formato non valido, voce duplicata, cancello non attivabile: solo un messaggio nel pannello,
 * niente registro); {@code TRANSIENT}/{@code PERMANENT}/{@code CONFIGURATION} per i guasti del provider (discovery irraggiungibile, segreto mancante).
 */
public class OAuthException extends RemoteServiceException {

    public OAuthException(String message, Throwable cause, Kind kind) {
        super(OAuthEventSource.OAUTH2, kind, message, cause);
    }

    /** Rifiuto applicativo atteso (messaggio gia' tradotto). */
    public OAuthException(String message) {
        this(message, null, Kind.REJECTED);
    }
}
