package org.dual.hexa.pwa.shell.application;

import java.util.List;
import org.dual.hexa.core.config.port.in.IModuleSettings;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.dual.hexa.pwa.shell.domain.PwaSettings;
import org.dual.hexa.pwa.shell.domain.WebManifest;
import org.dual.hexa.pwa.shell.domain.WebManifest.Icon;
import org.dual.hexa.pwa.shell.port.in.IPwa;
import org.dual.hexa.pwa.shell.port.out.IServiceWorkerSource;
import org.dual.hexa.core.web.BuildInfo;
import org.springframework.stereotype.Service;

/**
 * Compone manifest e service worker. Nome e nome breve vengono dal bundle dell'app ({@code app.title}, {@code app.brand}: le stesse chiavi del layout),
 * quindi per lingua. I colori sono esadecimali in configurazione (default = i token {@code canvas} di Tailwind): il manifest non puo' usare classi. Colori e display
 * vengono dalle impostazioni del modulo ({@code PwaSettings}) e si leggono a ogni richiesta: una modifica da {@code /settings} vale subito.
 */
@Service
class PwaService implements IPwa {

    static final String OFFLINE_PATH = "/offline";
    static final String ICON_BASE = "/pwa/icons/";

    private final IServiceWorkerSource source;
    private final Messages messages;
    private final BuildInfo buildInfo;
    private final IModuleSettings settings;

    PwaService(IServiceWorkerSource source, Messages messages, BuildInfo buildInfo, IModuleSettings settings) {
        this.source = source;
        this.messages = messages;
        this.buildInfo = buildInfo;
        this.settings = settings;
    }

    @Override
    public WebManifest manifest(String contextPath) {
        String root = contextPath + "/";
        String themeColor = themeColor();
        return new WebManifest(root, messages.get("app.title"), messages.get("app.brand"), root, root, settings.values(PwaSettings.ID).getString(PwaSettings.DISPLAY), themeColor, themeColor,
                List.of(new Icon(contextPath + ICON_BASE + "icon-192.png", "192x192", "image/png", "any"),
                        new Icon(contextPath + ICON_BASE + "icon-512.png", "512x512", "image/png", "any"),
                        new Icon(contextPath + ICON_BASE + "icon-maskable-512.png", "512x512", "image/png", "maskable"),
                        new Icon(contextPath + ICON_BASE + "icon.svg", "any", "image/svg+xml", "any")));
    }

    @Override
    public String serviceWorker(String contextPath) {
        return source.template()
                .replace("__SCOPE__", contextPath + "/")
                .replace("__OFFLINE_URL__", contextPath + OFFLINE_PATH)
                .replace("__CACHE_NAME__", "hexa-shell-" + cacheVersion());
    }

    /** Ora e commit della build (vedi {@code BuildInfo}): cambiano a ogni release; senza, un valore fisso. Solo caratteri sicuri per un nome di cache. */
    private String cacheVersion() {
        String raw = (buildInfo.getTime() == null ? "" : buildInfo.getTime()) + (buildInfo.getCommit() == null ? "" : buildInfo.getCommit());
        String safe = raw.replaceAll("[^A-Za-z0-9]", "");
        return safe.isEmpty() ? "dev" : safe;
    }

    @Override
    public String themeColor() {
        return settings.values(PwaSettings.ID).getString(PwaSettings.THEME_COLOR);
    }

    @Override
    public String themeColorDark() {
        return settings.values(PwaSettings.ID).getString(PwaSettings.THEME_COLOR_DARK);
    }
}
