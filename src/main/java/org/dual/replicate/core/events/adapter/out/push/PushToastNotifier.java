package org.dual.replicate.core.events.adapter.out.push;

import java.util.LinkedHashMap;
import java.util.Map;

import org.dual.replicate.core.events.domain.SystemEventSeverity;
import org.dual.replicate.core.events.port.out.IToastNotifier;
import org.dual.replicate.core.push.port.in.IClientPush;
import org.springframework.stereotype.Component;

/**
 * Consegna i toast delle serie di eventi a tutte le tab connesse a GET /events (evento SSE {@code system-event}, ri-dispatchato
 * come {@code system-toast} da fragments/live-events.html). Il payload ha gli stessi campi di quello dell'header HX-Trigger
 * (fragments/toast.html lo legge cosi'): key, message, transient, severity.
 */
@Component
public class PushToastNotifier implements IToastNotifier {

    static final String EVENT = "system-event";

    private final IClientPush push;

    public PushToastNotifier(IClientPush push) {
        this.push = push;
    }

    @Override
    public void notify(String key, String message, boolean transientFailure, SystemEventSeverity severity) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("key", key);
        payload.put("message", message);
        payload.put("transient", transientFailure);
        payload.put("severity", severity.name());
        push.emit(EVENT, payload);
    }
}
