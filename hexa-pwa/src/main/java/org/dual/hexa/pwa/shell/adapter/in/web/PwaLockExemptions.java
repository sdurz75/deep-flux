package org.dual.hexa.pwa.shell.adapter.in.web;

import java.util.List;
import org.dual.hexa.core.lock.port.in.ILockExemptPaths;
import org.springframework.stereotype.Component;

/** Con il blocco con PIN attivo il browser deve poter leggere manifest, service worker, pagina offline e icone anche senza sessione sbloccata. */
@Component
class PwaLockExemptions implements ILockExemptPaths {

    @Override
    public List<String> paths() {
        return List.of("/sw.js", "/offline", "/manifest.webmanifest", "/pwa/");
    }
}
