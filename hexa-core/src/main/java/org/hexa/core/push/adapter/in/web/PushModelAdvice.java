package org.hexa.core.push.adapter.in.web;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Espone a fragments/core/live-events.html i nomi degli eventi SSE dell'app: {@code app.push.client-events} (ri-dispatchati come
 * CustomEvent su {@code document.body}) e {@code app.push.reconnect-events} (quelli da ri-dispatchare alla riconnessione, perche'
 * gli eventi emessi a connessione caduta sono persi). {@code system-event} (toast) e' sempre gestito dal core.
 */
@ControllerAdvice
public class PushModelAdvice {

    private final List<String> clientEvents;
    private final List<String> reconnectEvents;

    public PushModelAdvice(@Value("${app.push.client-events:}") String clientEvents,
                           @Value("${app.push.reconnect-events:}") String reconnectEvents) {
        this.clientEvents = split(clientEvents);
        this.reconnectEvents = split(reconnectEvents);
    }

    @ModelAttribute("pushEvents")
    public List<String> pushEvents() {
        return clientEvents;
    }

    @ModelAttribute("pushReconnectEvents")
    public List<String> pushReconnectEvents() {
        return reconnectEvents;
    }

    private static List<String> split(String csv) {
        return Arrays.stream(csv.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
