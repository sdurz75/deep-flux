package org.dual.hexa.core.events.adapter.out.push;

import java.util.LinkedHashMap;
import java.util.Map;

import org.dual.hexa.core.events.port.in.INotifications;
import org.dual.hexa.core.push.port.in.IClientPush;
import org.springframework.stereotype.Component;

/**
 * Consegna le notifiche effimere a tutte le tab connesse a GET /events (evento SSE {@code notice}, ri-dispatchato come {@code system-toast}
 * da fragments/core/live-events.html). E' un evento DIVERSO da {@code system-event} (vedi {@code PushToastNotifier}): quello e' un errore o
 * un avviso del registro e fa anche aggiornare /system/events; un successo non deve toccare il registro.
 */
@Component
public class PushNotifications implements INotifications {

    static final String EVENT = "notice";
    static final String SEVERITY_SUCCESS = "SUCCESS";

    private final IClientPush push;

    public PushNotifications(IClientPush push) {
        this.push = push;
    }

    @Override
    public void success(String key, String message, String path) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("key", key);
        payload.put("message", message);
        payload.put("path", path);
        payload.put("severity", SEVERITY_SUCCESS);
        push.emit(EVENT, payload);
    }
}
