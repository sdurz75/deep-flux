package ${package}.example.domain;

import org.dual.hexa.core.kernel.remote.RemoteServiceException;

/**
 * Errore del servizio esterno di esempio. Il {@code Kind} decide tutto: {@code TRANSIENT} (rete, timeout, 408/429/5xx) viene ritentato da
 * {@code RemoteCaller}; {@code PERMANENT} (4xx, risposta illeggibile) e {@code CONFIGURATION} (URL o credenziali mancanti) no; {@code REJECTED} e' un rifiuto
 * atteso (niente registro). La source ({@code ExampleEventSource.EXAMPLE}) dice al registro eventi da dove arriva.
 */
public class ExampleRemoteException extends RemoteServiceException {

    public ExampleRemoteException(String message, Throwable cause, Kind kind) {
        super(ExampleEventSource.EXAMPLE, kind, message, cause);
    }
}
