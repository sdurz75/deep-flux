package org.dual.hexa.pwa.shell.domain;

import java.util.List;

/**
 * Il Web App Manifest dell'app (https://www.w3.org/TR/appmanifest/), in tipi di dominio: la forma JSON la decide l'adapter web.
 * {@code startUrl} e {@code scope} sono gia' comprensivi del context path (reverse proxy su sottopercorso).
 */
public record WebManifest(String id, String name, String shortName, String startUrl, String scope, String display, String themeColor,
                          String backgroundColor, List<Icon> icons) {

    /** Un'icona dell'app; {@code purpose} e' {@code any} o {@code maskable}. */
    public record Icon(String src, String sizes, String type, String purpose) {
    }
}
