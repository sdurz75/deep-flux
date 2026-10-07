package org.hexa.core.push.domain;

/** Un messaggio da inviare a tutte le tab connesse: nome dell'evento SSE e payload (serializzato in JSON dal trasporto). */
public record PushMessage(String event, Object data) {
}
