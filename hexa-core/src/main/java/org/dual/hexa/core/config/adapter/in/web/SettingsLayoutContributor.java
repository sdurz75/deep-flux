package org.dual.hexa.core.config.adapter.in.web;

import java.util.List;
import org.dual.hexa.core.config.port.in.IModuleSettings;
import org.dual.hexa.core.web.ILayoutContributor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** La voce «Impostazioni» del menu «Gestione» compare solo se almeno un modulo si e' registrato: il menu si autogenera. */
@Component
@Order(5)
class SettingsLayoutContributor implements ILayoutContributor {

    private final IModuleSettings settings;

    SettingsLayoutContributor(IModuleSettings settings) {
        this.settings = settings;
    }

    @Override
    public List<NavEntry> manageMenu() {
        return settings.modules().isEmpty() ? List.of() : List.of(new NavEntry("/settings", "settings.menu"));
    }
}
