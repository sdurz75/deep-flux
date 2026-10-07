package org.dual.hexa.pwa.shell.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.dual.hexa.pwa.shell.domain.WebManifest;
import org.dual.hexa.pwa.shell.port.in.IPwa;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Manifest, service worker e pagina offline. Il worker sta alla radice del context path ({@code /sw.js}) cosi' il suo scope copre l'app senza
 * {@code Service-Worker-Allowed}; manifest e worker sono sempre {@code no-cache}: il browser li rilegge e una nuova release si propaga.
 */
@Controller
public class PwaController {

    private static final MediaType MANIFEST = MediaType.parseMediaType("application/manifest+json");
    private static final MediaType JAVASCRIPT = MediaType.parseMediaType("text/javascript;charset=UTF-8");

    private final IPwa pwa;

    public PwaController(IPwa pwa) {
        this.pwa = pwa;
    }

    @GetMapping("/manifest.webmanifest")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> manifest(HttpServletRequest request) {
        return ResponseEntity.ok().contentType(MANIFEST).cacheControl(CacheControl.noCache()).body(toJson(pwa.manifest(request.getContextPath())));
    }

    @GetMapping("/sw.js")
    @ResponseBody
    public ResponseEntity<String> serviceWorker(HttpServletRequest request) {
        return ResponseEntity.ok().contentType(JAVASCRIPT).cacheControl(CacheControl.noCache()).body(pwa.serviceWorker(request.getContextPath()));
    }

    /** La shell offline: pagina intera col layout, precaricata dal worker. Mai servita come fragment. */
    @GetMapping("/offline")
    public String offline() {
        return "core/offline";
    }

    private static Map<String, Object> toJson(WebManifest manifest) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", manifest.id());
        json.put("name", manifest.name());
        json.put("short_name", manifest.shortName());
        json.put("start_url", manifest.startUrl());
        json.put("scope", manifest.scope());
        json.put("display", manifest.display());
        json.put("theme_color", manifest.themeColor());
        json.put("background_color", manifest.backgroundColor());
        List<Map<String, String>> icons = new ArrayList<>();
        for (WebManifest.Icon icon : manifest.icons()) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("src", icon.src());
            entry.put("sizes", icon.sizes());
            entry.put("type", icon.type());
            entry.put("purpose", icon.purpose());
            icons.add(entry);
        }
        json.put("icons", icons);
        return json;
    }
}
