package org.dual.hexa.pwa.shell.adapter.in.web;

import java.util.List;
import org.dual.hexa.core.config.domain.ConfigField;
import org.dual.hexa.core.config.port.in.IConfigModule;
import org.dual.hexa.pwa.shell.domain.PwaSettings;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Registra hexa-pwa fra i moduli configurabili del core: la sezione «PWA» di {@code /settings} compare da sola. Default = i token {@code canvas}
 * di Tailwind (il manifest non accetta classi). {@code app.pwa.*} in yml/env resta un valore di riserva, e {@code app.pwa.enabled} una decisione di
 * avvio (non modificabile a runtime).
 */
@Component
@Order(10)
class PwaConfigModule implements IConfigModule {

    @Override
    public String id() {
        return PwaSettings.ID;
    }

    @Override
    public String titleKey() {
        return "pwa.settings.title";
    }

    @Override
    public List<ConfigField> fields() {
        return List.of(
                ConfigField.color(PwaSettings.THEME_COLOR, "#ffffff", "pwa.settings.themeColor", "pwa.settings.themeColor.help"),
                ConfigField.color(PwaSettings.THEME_COLOR_DARK, "#0d1117", "pwa.settings.themeColorDark", "pwa.settings.themeColorDark.help"),
                ConfigField.select(PwaSettings.DISPLAY, "standalone", List.of("standalone", "minimal-ui", "fullscreen", "browser"),
                        "pwa.settings.display", "pwa.settings.display.help"));
    }
}
