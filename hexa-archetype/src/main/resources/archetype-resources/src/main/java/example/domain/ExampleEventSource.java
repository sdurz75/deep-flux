package ${package}.example.domain;

import org.dual.hexa.core.kernel.EventSource;

/**
 * Da dove vengono gli eventi di sistema di questa feature (il registro {@code /system/events}, la campanella, i toast). L'etichetta mostrata sta nel
 * bundle: {@code events.source.EXAMPLE} (in ENTRAMBE le lingue). Una feature che parla con un servizio esterno ne ha una propria, e la sua eccezione
 * estende {@code RemoteServiceException} portando questa source.
 */
public enum ExampleEventSource implements EventSource {
    EXAMPLE
}
