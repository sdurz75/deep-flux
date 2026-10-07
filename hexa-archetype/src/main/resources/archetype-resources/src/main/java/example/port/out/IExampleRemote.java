package ${package}.example.port.out;

/** Il servizio esterno di esempio (lo implementa un client HTTP in {@code adapter.out.remote}). */
public interface IExampleRemote {

    /**
     * Interroga {@code GET <base-url>/status} (idempotente: ritentato sui guasti transitori).
     *
     * @throws ${package}.example.domain.ExampleRemoteException {@code CONFIGURATION} se l'URL non e' impostato, {@code TRANSIENT}/{@code PERMANENT} sui guasti
     */
    String fetchStatus();
}
