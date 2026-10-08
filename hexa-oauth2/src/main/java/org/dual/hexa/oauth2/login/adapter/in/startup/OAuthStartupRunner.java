package org.dual.hexa.oauth2.login.adapter.in.startup;

import org.dual.hexa.core.events.port.in.ISystemEvents;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.dual.hexa.oauth2.login.domain.GateStatus;
import org.dual.hexa.oauth2.login.domain.OAuthEventSource;
import org.dual.hexa.oauth2.login.port.in.IOAuthAccess;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Due controlli all'avvio. {@code HX_OAUTH2_RESET=true} spegne il cancello senza condizioni (recupero se ci si e' chiusi fuori: chi puo' cambiare l'ambiente
 * del server puo' leggere il database comunque) e lo scrive negli eventi; si toglie dopo l'uso. {@code HX_OAUTH2_ENABLED=true} senza provider o senza
 * ammessi lascia l'app chiusa a tutti (fallisce chiuso): lo si segnala negli eventi, con il rimedio.
 */
@Component
@Profile("!backup")
class OAuthStartupRunner implements ApplicationRunner {

    private final IOAuthAccess access;
    private final ISystemEvents events;
    private final Messages messages;

    OAuthStartupRunner(IOAuthAccess access, ISystemEvents events, Messages messages) {
        this.access = access;
        this.events = events;
        this.messages = messages;
    }

    @Override
    public void run(ApplicationArguments args) {
        GateStatus status = access.status();
        if (status.resetActive()) {
            access.reset();
            events.warn(OAuthEventSource.OAUTH2, "oauth2Reset", null, messages.get("oauth2.event.reset"));
        } else if (status.requestedByEnv() && (status.providers() == 0 || status.allowed() == 0)) {
            events.warn(OAuthEventSource.OAUTH2, "oauth2EnvIncomplete", null, messages.get("oauth2.event.envIncomplete"));
        }
    }
}
