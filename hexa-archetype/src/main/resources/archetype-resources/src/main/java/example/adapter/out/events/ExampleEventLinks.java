package ${package}.example.adapter.out.events;

import java.util.List;
import org.hexa.core.events.domain.EventLink;
import org.hexa.core.events.port.out.IEventLinkResolver;
import org.hexa.core.kernel.i18n.Messages;
import org.springframework.stereotype.Component;

/**
 * Punto di estensione del core: dal {@code subject} di un evento ({@code example:12}) al link "apri" mostrato nel registro eventi. Con piu' feature
 * si tiene UN solo resolver per l'app (in {@code shared}) che le conosce tutte; qui, con una sola feature, sta nella feature.
 */
@Component
public class ExampleEventLinks implements IEventLinkResolver {

    private static final String PREFIX = "example:";

    private final Messages messages;

    public ExampleEventLinks(Messages messages) {
        this.messages = messages;
    }

    @Override
    public List<EventLink> resolve(String subject) {
        if (subject == null || !subject.startsWith(PREFIX)) {
            return List.of();
        }
        return List.of(new EventLink("/example", messages.get("events.link.example")));
    }
}
