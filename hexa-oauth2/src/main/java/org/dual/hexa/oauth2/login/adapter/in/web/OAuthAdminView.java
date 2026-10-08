package org.dual.hexa.oauth2.login.adapter.in.web;

import java.util.List;
import org.dual.hexa.core.tokens.port.in.IApiTokens;
import org.dual.hexa.oauth2.login.domain.AllowedEntry;
import org.dual.hexa.oauth2.login.domain.GateStatus;
import org.dual.hexa.oauth2.login.domain.ProviderConfig;

/** Tutto cio' che il pannello «Accesso» mostra, in un solo oggetto: cosi' il fragment ha un solo parametro. */
record OAuthAdminView(GateStatus status, List<ProviderRow> providers, List<AllowedEntry> allowed, List<IApiTokens.TokenView> secrets,
                      boolean tokensConfigured, String error, boolean saved) {

    /** Un provider con l'indirizzo di ritorno da registrare presso il provider (dipende da host e context path di questa richiesta). */
    record ProviderRow(ProviderConfig config, String redirectUri) {
    }
}
