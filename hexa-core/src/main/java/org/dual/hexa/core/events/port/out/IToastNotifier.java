package org.dual.hexa.core.events.port.out;

import org.dual.hexa.core.events.domain.SystemEventSeverity;

/** Notifica un toast a tutte le tab aperte (alla prima occorrenza di una serie di eventi). Non lancia mai verso il chiamante. */
public interface IToastNotifier {

    /**
     * @param key               identifica il toast lato client per la dedupe (SSE e HX-Trigger possono portare lo stesso evento)
     * @param message           gia' tradotto
     * @param transientFailure  servizio temporaneamente non raggiungibile (il toast suggerisce di riprovare)
     * @param severity          decide lo stile (ERROR = rosso, WARNING = avviso)
     */
    void notify(String key, String message, boolean transientFailure, SystemEventSeverity severity);
}
