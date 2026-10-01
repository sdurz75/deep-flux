package org.dual.replicate.core.push.adapter.in.events;

import org.dual.replicate.core.push.port.in.IClientPush;
import org.dual.replicate.core.push.port.in.IPushEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Inoltra alle tab connesse ogni evento applicativo che implementa {@link IPushEvent} (es. il toast di un evento di sistema). */
@Component
public class PushEventForwarder {

    private final IClientPush push;

    public PushEventForwarder(IClientPush push) {
        this.push = push;
    }

    @EventListener
    public void on(IPushEvent event) {
        push.emit(event.pushName(), event.pushData());
    }
}
