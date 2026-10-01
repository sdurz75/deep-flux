package org.dual.replicate.domain.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.dual.replicate.core.push.port.in.IPushEvent;
import org.dual.replicate.domain.SystemEventSeverity;

/**
 * Un evento di sistema da mostrare come toast in tutte le tab connesse a GET /events
 * (evento SSE "system-event", vedi core.push.PushEventForwarder). {@code key}
 * identifica il toast lato client per la dedupe (SSE e HX-Trigger possono
 * portare lo stesso evento); {@code message} e' gia' tradotto; {@code transientFailure} = servizio
 * temporaneamente non raggiungibile (il toast suggerisce di riprovare piu' tardi); {@code severity} decide lo stile
 * (ERROR = rosso, WARNING = avviso). Il campo JSON si chiama {@code transient} come nel payload di HX-Trigger
 * (fragments/toast.html lo legge cosi': senza l'annotazione l'SSE emetterebbe {@code transientFailure} e il suggerimento
 * "riprova tra qualche istante" non arriverebbe).
 */
public record SystemToastEvent(String key, String message, @JsonProperty("transient") boolean transientFailure,
                               SystemEventSeverity severity) implements IPushEvent {

    @Override
    public String pushName() {
        return "system-event";
    }

    @Override
    public Object pushData() {
        return this;
    }

    public SystemToastEvent(String key, String message, boolean transientFailure) {
        this(key, message, transientFailure, SystemEventSeverity.ERROR);
    }

    public SystemToastEvent(String key, String message) {
        this(key, message, false);
    }
}
