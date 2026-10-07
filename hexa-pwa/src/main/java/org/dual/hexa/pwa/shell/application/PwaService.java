package org.dual.hexa.pwa.shell.application;

import java.util.List;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.dual.hexa.pwa.shell.domain.WebManifest;
import org.dual.hexa.pwa.shell.domain.WebManifest.Icon;
import org.dual.hexa.pwa.shell.port.in.IPwa;
import org.dual.hexa.pwa.shell.port.out.IServiceWorkerSource;
import org.dual.hexa.core.web.BuildInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Compone manifest e service worker. Nome e nome breve vengono dal bundle dell'app ({@code app.title}, {@code app.brand}: le stesse chiavi del layout),
 * quindi per lingua. I colori sono esadecimali in configurazione (default = i token {@code canvas} di Tailwind): il manifest non puo' usare classi.
 */
@Service
class PwaService implements IPwa {

    static final String OFFLINE_PATH = "/offline";
    static final String ICON_BASE = "/pwa/icons/";

    private final IServiceWorkerSource source;
    private final Messages messages;
    private final BuildInfo buildInfo;
    private final String themeColor;
    private final String themeColorDark;
    private final String display;

    PwaService(IServiceWorkerSource source, Messages messages, BuildInfo buildInfo,
               @Value("${app.pwa.theme-color:#ffffff}") String themeColor,
               @Value("${app.pwa.theme-color-dark:#0d1117}") String themeColorDark,
               @Value("${app.pwa.display:standalone}") String display) {
        this.source = source;
        this.messages = messages;
        this.buildInfo = buildInfo;
        this.themeColor = themeColor;
        this.themeColorDark = themeColorDark;
        this.display = display;
    }

    @Override
    public WebManifest manifest(String contextPath) {
        String root = contextPath + "/";
        return new WebManifest(root, messages.get("app.title"), messages.get("app.brand"), root, root, display, themeColor, themeColor,
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
        return themeColor;
    }

    @Override
    public String themeColorDark() {
        return themeColorDark;
    }
}
