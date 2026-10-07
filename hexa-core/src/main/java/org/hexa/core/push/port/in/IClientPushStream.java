package org.hexa.core.push.port.in;

import org.hexa.core.push.domain.PushMessage;

import reactor.core.publisher.Flux;

/** Il flusso dei messaggi per UNA tab (una sottoscrizione a {@code GET /events}), con buffer proprio. */
public interface IClientPushStream {

    Flux<PushMessage> subscribe();
}
