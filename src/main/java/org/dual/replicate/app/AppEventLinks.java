package org.dual.replicate.app;

import java.util.List;

import org.dual.replicate.core.events.domain.EventLink;
import org.dual.replicate.core.events.port.out.IEventLinkResolver;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.springframework.stereotype.Component;

/** Link dagli eventi alle pagine dell'app: {@code generation:<id>} -> /generations/{id}, {@code conversation:<id>} -> /deep-chat/{id}. */
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
            default -> List.of();
        };
    }
}
