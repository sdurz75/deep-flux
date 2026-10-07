package org.dual.hexa.pwa.shell.port.out;

/** Il sorgente del service worker, con i segnaposto {@code __SCOPE__}, {@code __OFFLINE_URL__} e {@code __CACHE_NAME__}. */
public interface IServiceWorkerSource {

    String template();
}
