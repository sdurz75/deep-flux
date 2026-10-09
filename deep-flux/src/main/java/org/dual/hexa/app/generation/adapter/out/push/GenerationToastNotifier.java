package org.dual.hexa.app.generation.adapter.out.push;

import org.dual.hexa.app.generation.domain.Generation;
import org.dual.hexa.app.generation.domain.GenerationStatus;
import org.dual.hexa.app.generation.domain.event.GenerationCompletedEvent;
import org.dual.hexa.core.events.port.in.INotifications;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Un toast di successo, su qualunque pagina aperta, a ogni generazione riuscita ({@code INotifications}, core.events): i fallimenti hanno
 * gia' i loro errori nel registro eventi. Come {@code GalleryPushNotifier} ascolta {@code GenerationCompletedEvent}, l'unico punto che copre
 * sia il polling di /generations/{id} sia il watcher in background della chat. Il testo e' tradotto qui: con il polling vale la lingua della
 * richiesta, dal watcher quella di default (limite noto: la notifica porta il testo gia' risolto).
 */
@Component
public class GenerationToastNotifier {

    private final INotifications notifications;
    private final Messages messages;

    public GenerationToastNotifier(INotifications notifications, Messages messages) {
        this.notifications = notifications;
        this.messages = messages;
    }

    @EventListener
    public void onGenerationCompleted(GenerationCompletedEvent event) {
        Generation generation = event.generation();
        if (generation.getStatus() != GenerationStatus.SUCCEEDED || generation.isImported()) {
            return;
        }
        String code = generation.isVideo() ? "generation.toast.doneVideo" : "generation.toast.doneImage";
        notifications.success("generation-" + generation.getId(), messages.get(code, generation.getId()),
                "/generations/" + generation.getId());
    }
}
