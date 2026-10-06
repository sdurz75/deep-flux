package org.dual.replicate.core.kernel;

/**
 * Da dove viene un evento di sistema (un servizio remoto, lo storage, il codice interno...). Interfaccia nel kernel perche'
 * la espone {@code RemoteServiceException} e la persistono gli eventi: il core ne ha i propri valori
 * ({@code CoreEventSource}), l'app aggiunge i suoi ({@code AppEventSource}).
 * <p>
 * L'etichetta mostrata all'utente NON sta qui ma nel bundle: chiave {@code events.source.<name>}.
 */
public interface EventSource {

    /** Identificativo stabile, persistito in {@code system_event.source} (varchar). */
    String name();
}
