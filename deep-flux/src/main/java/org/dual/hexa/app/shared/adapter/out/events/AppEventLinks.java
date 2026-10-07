package org.dual.hexa.app.shared.adapter.out.events;

import java.util.List;

import org.dual.hexa.app.shared.domain.AppEventSubjects;
import org.dual.hexa.core.events.domain.EventLink;
import org.dual.hexa.core.events.port.out.IEventLinkResolver;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.springframework.stereotype.Component;

/** Link dagli eventi alle pagine dell'app: {@code generation:<id>} -> /generations/{id}, {@code conversation:<id>} -> /deep-chat/{id}, {@code training:<id>} -> /trainings/{id}, {@code trainingDataset:<id>} -> /trainings/datasets/{id}. */
@Component
public class AppEventLinks implements IEventLinkResolver {

    private final Messages messages;

    public AppEventLinks(Messages messages) {
        this.messages = messages;
    }

    @Override
    public List<EventLink> resolve(String subject) {
        if (subject == null) {
            return List.of();
        }
        int colon = subject.indexOf(':');
        if (colon < 0) {
            return List.of();
        }
        String type = subject.substring(0, colon);
        String raw = subject.substring(colon + 1);
        long id;
        try {
            id = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return List.of();
        }
        return switch (type) {
            case AppEventSubjects.GENERATION -> List.of(new EventLink("/generations/" + id, messages.get("events.link.generation", id)));
            case AppEventSubjects.CONVERSATION -> List.of(new EventLink("/deep-chat/" + id, messages.get("events.link.conversation")));
            case AppEventSubjects.TRAINING -> List.of(new EventLink("/trainings/" + id, messages.get("events.link.training", id)));
            case AppEventSubjects.TRAINING_DATASET -> List.of(new EventLink("/trainings/datasets/" + id, messages.get("events.link.trainingDataset", id)));
            default -> List.of();
        };
    }
}
