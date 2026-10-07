package org.hexa.app.chat.port.in;

/**
 * Rete di sicurezza della chat: nessuna generazione avviata da /deep-chat resta senza il suo watcher o senza il suo turno di esito.
 */
public interface IChatRecovery {

    /** All'avvio: scrive i turni mancanti e riavvia il watcher perso delle generazioni ancora in corso. */
    void recoverOnStartup();

    /** Periodico: scrive i turni mancanti delle generazioni terminali (a distanza dal completamento). */
    void sweep();
}
