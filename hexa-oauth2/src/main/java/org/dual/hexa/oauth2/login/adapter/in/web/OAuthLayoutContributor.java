package org.dual.hexa.oauth2.login.adapter.in.web;

import java.util.List;
import org.dual.hexa.core.web.ILayoutContributor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Innesta nel menu «Gestione» del core la voce «Accesso» (pagina {@code /oauth2}); il core non conosce questa libreria. */
@Component
@Order(30)
class OAuthLayoutContributor implements ILayoutContributor {

    @Override
    public List<NavEntry> manageMenu() {
        return List.of(new NavEntry("/oauth2", "oauth2.menu.title"));
    }
}
