package org.dual.hexa.ai.chat.adapter.in.web;

import java.util.List;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * I path dell'host che la chat trasforma in link (solo in visualizzazione, relativi alla pagina per un reverse proxy su subpath).
 * Elenco CHIUSO e configurabile: {@code app.chat.link-paths} sono frammenti di espressione regolare (senza lo {@code /} iniziale, i piu'
 * lunghi per primi) e {@code app.chat.entity-link-paths} i prefissi dei path {@code /<prefisso>/<id>} che si mostrano come {@code #id}.
 * Un path nuovo citabile va aggiunto in configurazione e nella sezione di prompt che descrive l'app. Senza configurazione la chat non linka nulla.
 * Qui anche {@code app.chat.host-fragment}: il template dell'host che riempie gli slot della pagina (vedi {@code core/deep-chat.html}).
 */
@Component
class ChatPageConfig {

    private final String alternatives;
    private final String entities;
    private final String hostFragment;

    ChatPageConfig(Environment environment) {
        Binder binder = Binder.get(environment);
        this.alternatives = String.join("|", list(binder, "app.chat.link-paths"));
        this.entities = String.join("|", list(binder, "app.chat.entity-link-paths"));
        this.hostFragment = binder.bind("app.chat.host-fragment", String.class).orElse("");
    }

    private static List<String> list(Binder binder, String name) {
        return binder.bind(name, Bindable.listOf(String.class)).orElse(List.of());
    }

    String alternatives() {
        return alternatives;
    }

    String entities() {
        return entities;
    }

    /** Il template dell'host con i punti di innesto della pagina ({@code intro}, {@code settings}, {@code below}, {@code scripts}, {@code conversationTags}, {@code knownTags}); vuoto = nessun host. */
    String hostFragment() {
        return hostFragment;
    }
}
