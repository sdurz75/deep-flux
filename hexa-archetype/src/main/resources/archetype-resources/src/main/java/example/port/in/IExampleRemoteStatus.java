package ${package}.example.port.in;

/** Cio' che la feature offre per controllare il servizio esterno di esempio. */
public interface IExampleRemoteStatus {

    /** Lo stato riportato dal servizio; un guasto risale come {@code ExampleRemoteException} (la registra il resolver del core, che mostra il toast). */
    String check();
}
