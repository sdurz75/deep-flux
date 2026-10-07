package org.dual.hexa.pwa.shell.adapter.in.web;

import org.dual.hexa.pwa.shell.port.in.IPwa;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** I colori della barra del browser per {@code fragments/core/pwa-head.html}; il fragment lo inserisce {@code PwaLayoutContributor}. */
@ControllerAdvice
public class PwaHeadAdvice {

    private final IPwa pwa;

    public PwaHeadAdvice(IPwa pwa) {
        this.pwa = pwa;
    }

    @ModelAttribute("pwaThemeColor")
    public String themeColor() {
        return pwa.themeColor();
    }

    @ModelAttribute("pwaThemeColorDark")
    public String themeColorDark() {
        return pwa.themeColorDark();
    }
}
