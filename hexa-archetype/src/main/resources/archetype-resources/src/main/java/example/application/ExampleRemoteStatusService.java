package ${package}.example.application;

import ${package}.example.port.in.IExampleRemoteStatus;
import ${package}.example.port.out.IExampleRemote;
import org.springframework.stereotype.Service;

/**
 * Nessun {@code catch}: l'eccezione {@code RemoteServiceException} risale fino al controller e da li' al resolver del core, che la registra UNA volta
 * ({@code ISystemEvents#record}), risponde 502/500 (422 se {@code REJECTED}, senza registro) e manda il toast. Un servizio che invece ingoia l'errore
 * (lavoro in background) lo registra da se', come fa {@code ExampleService}.
 */
@Service
public class ExampleRemoteStatusService implements IExampleRemoteStatus {

    private final IExampleRemote remote;

    public ExampleRemoteStatusService(IExampleRemote remote) {
        this.remote = remote;
    }

    @Override
    public String check() {
        return remote.fetchStatus();
    }
}
