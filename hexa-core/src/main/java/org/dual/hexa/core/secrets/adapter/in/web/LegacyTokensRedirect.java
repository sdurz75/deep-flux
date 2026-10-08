package org.dual.hexa.core.secrets.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * La vecchia pagina {@code /tokens} (ora {@code /secrets}): redirect permanente, per i segnalibri e per i link nella cronologia delle chat. Il redirect e'
 * relativo al context path.
 */
@Controller
class LegacyTokensRedirect {

    @GetMapping("/tokens")
    void redirect(HttpServletRequest request, HttpServletResponse response) {
        response.setStatus(HttpStatus.MOVED_PERMANENTLY.value());
        response.setHeader(HttpHeaders.LOCATION, request.getContextPath() + "/secrets");
    }
}
