package org.dual.hexa.oauth2.login.application;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.dual.hexa.core.config.domain.ModuleConfigChangedEvent;
import org.dual.hexa.core.config.domain.ModuleValues;
import org.dual.hexa.core.config.port.in.IModuleSettings;
import org.dual.hexa.core.events.port.in.ISystemEvents;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.dual.hexa.oauth2.login.domain.AccessDecision;
import org.dual.hexa.oauth2.login.domain.ConfigKeys;
import org.dual.hexa.oauth2.login.domain.GateStatus;
import org.dual.hexa.oauth2.login.domain.OAuthEventSource;
import org.dual.hexa.oauth2.login.domain.OAuthException;
import org.dual.hexa.oauth2.login.domain.OAuthLogin;
import org.dual.hexa.oauth2.login.port.in.IOAuthAccess;
import org.dual.hexa.oauth2.login.port.out.ILoginStore;
import org.dual.hexa.oauth2.login.port.out.IOAuthEnvironment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Il cancello e la decisione di accesso. L'interruttore, i provider e gli elenchi sono campi del modulo di configurazione {@code oauth2}
 * ({@code IModuleSettings}); {@link #isEnabled()} e' chiamato a OGNI richiesta e usa uno stato in memoria (l'app e' a istanza singola), caricato alla prima
 * lettura e invalidato dopo ogni salvataggio delle impostazioni del modulo. Fallisce CHIUSO: acceso senza provider o senza ammessi, nessuno entra.
 */
@Service
class OAuthAccessService implements IOAuthAccess {

    private final IModuleSettings settings;
    private final ILoginStore logins;
    private final IOAuthEnvironment env;
    private final ISystemEvents events;
    private final Messages messages;
    private final Clock clock;
    private volatile Boolean configured;

    @Autowired
    OAuthAccessService(IModuleSettings settings, ILoginStore logins, IOAuthEnvironment env, ISystemEvents events, Messages messages) {
        this(settings, logins, env, events, messages, Clock.systemUTC());
    }

    OAuthAccessService(IModuleSettings settings, ILoginStore logins, IOAuthEnvironment env, ISystemEvents events, Messages messages, Clock clock) {
        this.settings = settings;
        this.logins = logins;
        this.env = env;
        this.events = events;
        this.messages = messages;
        this.clock = clock;
    }

    @Override
    public boolean isEnabled() {
        if (env.reset()) {
            return false;
        }
        return env.gateEnabled() || configured();
    }

    private boolean configured() {
        Boolean cached = configured;
        if (cached == null) {
            cached = settings.values(ConfigKeys.MODULE).getBoolean(ConfigKeys.ENABLED);
            configured = cached;
        }
        return cached;
    }

    /** Dopo un salvataggio delle impostazioni del modulo: l'interruttore si rilegge; se sono cambiati i provider gli accessi di prova non valgono piu'. */
    @EventListener
    public void onSettingsChanged(ModuleConfigChangedEvent event) {
        if (!ConfigKeys.MODULE.equals(event.moduleId())) {
            return;
        }
        configured = null;
        if (event.keys().contains(ConfigKeys.PROVIDERS)) {
            logins.deleteAll();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public GateStatus status() {
        ModuleValues values = settings.values(ConfigKeys.MODULE);
        int providerCount = values.getRows(ConfigKeys.PROVIDERS).size() + (env.provider().isPresent() ? 1 : 0);
        int allowedCount = emails(values).size() + domains(values).size();
        boolean verified = logins.count() > 0;
        return new GateStatus(isEnabled(), env.gateEnabled(), env.reset(), providerCount, allowedCount, verified,
                providerCount > 0 && allowedCount > 0 && verified);
    }

    @Override
    public void checkChange(boolean wasEnabled, boolean enable, int providerRows, int allowedCount) {
        if (!enable) {
            if (env.gateEnabled() && !env.reset()) {
                throw new OAuthException(messages.get("oauth2.error.enabledByEnv"));
            }
            return;
        }
        if (env.gateEnabled()) {
            return; // la responsabilita' e' del deployer
        }
        if (providerRows + (env.provider().isPresent() ? 1 : 0) == 0) {
            throw new OAuthException(messages.get(wasEnabled ? "oauth2.error.lastProvider" : "oauth2.error.noProvider"));
        }
        if (allowedCount == 0) {
            throw new OAuthException(messages.get(wasEnabled ? "oauth2.error.lastAllowed" : "oauth2.error.noAllowed"));
        }
        if (!wasEnabled && logins.count() == 0) {
            throw new OAuthException(messages.get("oauth2.error.noVerifiedLogin"));
        }
    }

    @Override
    public void reset() {
        if (settings.values(ConfigKeys.MODULE).getBoolean(ConfigKeys.ENABLED)) {
            settings.save(ConfigKeys.MODULE, Map.of(ConfigKeys.ENABLED, "false"));
        }
        configured = null;
    }

    @Override
    @Transactional
    public AccessDecision authorize(String issuer, String email, boolean emailVerified, String subject) {
        String normalized = AllowPolicy.normalizeEmail(email);
        AccessDecision decision = decide(issuer, normalized, emailVerified, subject);
        if (!decision.allowed()) {
            events.warn(OAuthEventSource.OAUTH2, "loginDenied", "oauth2:denied",
                    messages.get("oauth2.event.denied", normalized.isEmpty() ? "?" : normalized, decision.reason().name()));
        }
        return decision;
    }

    private AccessDecision decide(String issuer, String email, boolean emailVerified, String subject) {
        if (email.isEmpty() || !AllowPolicy.isEmail(email)) {
            return AccessDecision.deny(AccessDecision.Reason.EMAIL_MISSING);
        }
        if (!emailVerified) {
            return AccessDecision.deny(AccessDecision.Reason.EMAIL_UNVERIFIED);
        }
        ModuleValues values = settings.values(ConfigKeys.MODULE);
        boolean exact = emails(values).contains(email);
        if (!exact && !domains(values).contains(AllowPolicy.domainOf(email))) {
            return AccessDecision.deny(AccessDecision.Reason.NOT_LISTED);
        }
        Optional<OAuthLogin> prior = logins.find(email);
        if (exact && prior.isPresent() && !prior.get().matches(issuer, subject)) {
            return AccessDecision.deny(AccessDecision.Reason.IDENTITY_MISMATCH);
        }
        OAuthLogin login = prior.orElseGet(() -> new OAuthLogin(email, issuer, subject, clock.instant()));
        login.loggedIn(issuer, subject, clock.instant());
        logins.save(login);
        return AccessDecision.allow();
    }

    private static List<String> emails(ModuleValues values) {
        return values.getList(ConfigKeys.ALLOWED_EMAILS).stream().map(AllowPolicy::normalizeEmail).filter(v -> !v.isEmpty()).distinct().toList();
    }

    private static List<String> domains(ModuleValues values) {
        return values.getList(ConfigKeys.ALLOWED_DOMAINS).stream().map(AllowPolicy::normalizeDomain).filter(v -> !v.isEmpty()).distinct().toList();
    }
}
