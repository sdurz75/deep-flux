package org.dual.hexa.oauth2.login.adapter.in.web;

import org.dual.hexa.oauth2.login.port.in.IOAuthProviders;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * La pagina di accesso (full-screen modal, come lo sblocco del PIN) e il suo punto di partenza. {@code /oauth2/start/{slug}} esiste per non mandare l'utente
 * sul flusso di Spring a occhi chiusi: prima verifica che la registrazione del provider si costruisca (discovery, segreto), e se non si puo' torna qui con un
 * errore invece di una pagina 500 (il guasto e' gia' negli eventi).
 */
@Controller
class OAuthLoginController {

    private final IOAuthProviders providers;
    private final ClientRegistrationRepository registrations;

    OAuthLoginController(IOAuthProviders providers, ClientRegistrationRepository registrations) {
        this.providers = providers;
        this.registrations = registrations;
    }

    @GetMapping(OAuthRequests.LOGIN_PATH)
    String login(@RequestParam(required = false) String error, Model model) {
        model.addAttribute("providers", providers.list());
        model.addAttribute("error", error);
        return "core/oauth2-login";
    }

    @GetMapping("/oauth2/start/{slug}")
    String start(@PathVariable String slug) {
        if (registrations.findByRegistrationId(slug) == null) {
            return "redirect:" + OAuthRequests.LOGIN_PATH + "?error=provider";
        }
        return "redirect:/oauth2/authorization/" + slug;
    }
}
