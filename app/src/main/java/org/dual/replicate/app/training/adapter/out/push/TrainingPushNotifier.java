package org.dual.replicate.app.training.adapter.out.push;

import org.dual.replicate.app.training.domain.event.TrainingChangedEvent;
import org.dual.replicate.core.push.port.in.IClientPush;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Il lato training del push verso lo storico ({@code /trainings?tab=history}): traduce {@link TrainingChangedEvent} nell'evento SSE {@code training-update}
 * (generico: "qualcosa e' cambiato, ricarica", come {@code gallery-update}) e lo consegna a {@link IClientPush} (core.push), che lo manda a tutte le tab
 * connesse a GET /events. L'evento parte a ogni creazione, cambio di stato ed eliminazione, anche quando a muoverlo e' il poller di recupero con la pagina chiusa.
 */
@Component
class TrainingPushNotifier {

    static final String TRAINING_UPDATE = "training-update";

    private final IClientPush push;

    TrainingPushNotifier(IClientPush push) {
        this.push = push;
    }

    @EventListener
    void onTrainingChanged(TrainingChangedEvent event) {
        push.emit(TRAINING_UPDATE, "refresh");
    }
}
