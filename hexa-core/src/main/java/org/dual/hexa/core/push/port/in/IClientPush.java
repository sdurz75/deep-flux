package org.dual.hexa.core.push.port.in;

/** Invia un evento a tutte le tab connesse a {@code GET /events}. Non lancia mai: senza tab connesse l'evento va perso (e' atteso). */
public interface IClientPush {

    void emit(String eventName, Object data);
}
