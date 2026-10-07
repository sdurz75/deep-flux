package org.dual.hexa.pwa.shell.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.dual.hexa.core.web.ILayoutContributor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Innesta manifest, theme-color, icone e registrazione del service worker nel {@code <head>} del layout del core (che non conosce questa libreria). */
@Component
@Order(10)
class PwaLayoutContributor implements ILayoutContributor {

    @Override
    public List<String> head(HttpServletRequest request) {
        return List.of("fragments/core/pwa-head :: head");
    }
}
