package org.dual.replicate.core.push.port.in;

/**
 * Un evento applicativo (pubblicato con {@code ApplicationEventPublisher}) che va anche inoltrato alle tab connesse:
 * {@code PushEventForwarder} lo ascolta e lo emette con {@link #pushName()} e {@link #pushData()}.
 */
public interface IPushEvent {

    /** Nome dell'evento SSE (e del CustomEvent lato client). */
    String pushName();

    /** Payload, serializzato in JSON. */
    Object pushData();
}
