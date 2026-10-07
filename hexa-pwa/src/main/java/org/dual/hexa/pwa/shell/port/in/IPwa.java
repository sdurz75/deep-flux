package org.dual.hexa.pwa.shell.port.in;

import org.dual.hexa.pwa.shell.domain.WebManifest;

/** Cio' che serve a un browser per installare l'app e tenerne la shell offline. */
public interface IPwa {

    /** Il manifest, nella lingua corrente; {@code contextPath} e' quello della richiesta ({@code ""} o {@code /sottopercorso}). */
    WebManifest manifest(String contextPath);

    /**
     * Il service worker: stesso sorgente per tutti, con scope, URL della pagina offline e nome della cache (legato alla build: una nuova release
     * invalida le cache vecchie) gia' sostituiti.
     */
    String serviceWorker(String contextPath);

    /** Colore della barra del browser, tema chiaro e scuro (esadecimali: il manifest e i meta non accettano token Tailwind). */
    String themeColor();

    String themeColorDark();
}
