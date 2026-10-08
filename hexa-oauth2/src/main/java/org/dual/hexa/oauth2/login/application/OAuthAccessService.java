package org.dual.hexa.oauth2.login.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.dual.hexa.core.events.port.in.ISystemEvents;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.dual.hexa.oauth2.login.domain.AccessDecision;
import org.dual.hexa.oauth2.login.domain.AllowKind;
import org.dual.hexa.oauth2.login.domain.AllowedUser;
import org.dual.hexa.oauth2.login.domain.GateStatus;
import org.dual.hexa.oauth2.login.domain.OAuthEventSource;
import org.dual.hexa.oauth2.login.domain.OAuthException;
import org.dual.hexa.oauth2.login.domain.OAuthGate;
import org.dual.hexa.oauth2.login.port.in.IOAuthAccess;
import org.dual.hexa.oauth2.login.port.out.IAllowedUserStore;
import org.dual.hexa.oauth2.login.port.out.IGateStore;
import org.dual.hexa.oauth2.login.port.out.IOAuthEnvironment;
import org.dual.hexa.oauth2.login.port.out.IProviderStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Il cancello e la decisione di accesso. {@link #isEnabled()} e' chiamato a OGNI richiesta: usa uno stato in memoria (l'app e' a istanza singola),
 * caricato alla prima lettura e rinfrescato a ogni modifica. Fallisce CHIUSO: acceso senza provider o senza ammessi, nessuno entra.
 */
@Service
class OAuthAccessService implements IOAuthAccess {

    private final IGateStore gates;
    private final IAllowedUserStore allowed;
    private final IProviderStore providers;
    private final IOAuthEnvironment env;
    private final ISystemEvents events;
    private final Messages messages;
    private final Clock clock;
    private volatile Boolean uiEnabled;

    @Autowired
    OAuthAccessService(IGateStore gates, IAllowedUserStore allowed, IProviderStore providers, IOAuthEnvironment env, ISystemEvents events, Messages messages) {
        this(gates, allowed, providers, env, events, messages, Clock.systemUTC());
    }

    OAuthAccessService(IGateStore gates, IAllowedUserStore allowed, IProviderStore providers, IOAuthEnvironment env, ISystemEvents events, Messages messages,
                       Clock clock) {
        this.gates = gates;
        this.allowed = allowed;
        this.providers = providers;
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
        return env.gateEnabled() || uiEnabled();
    }

    private boolean uiEnabled() {
        Boolean cached = uiEnabled;
        if (cached == null) {
            cached = gates.find().map(OAuthGate::isEnabled).orElse(false);
            uiEnabled = cached;
        }
        return cached;
    }

    @Override
    @Transactional(readOnly = true)
    public GateStatus status() {
        int providerCount = (int) providers.count() + (env.provider().isPresent() ? 1 : 0);
        int allowedCount = (int) allowed.count() + env.allowedEmails().size() + env.allowedDomains().size();
        boolean verified = gates.find().map(gate -> gate.getVerifiedLoginAt() != null).orElse(false);
        return new GateStatus(isEnabled(), env.gateEnabled(), env.reset(), providerCount, allowedCount, verified,
                providerCount > 0 && allowedCount > 0 && verified);
    }

    @Override
    @Transactional
    public void enable() {
        GateStatus status = status();
        if (status.providers() == 0) {
            throw new OAuthException(messages.get("oauth2.error.noProvider"));
        }
        if (status.allowed() == 0) {
            throw new OAuthException(messages.get("oauth2.error.noAllowed"));
        }
        if (!status.verifiedLogin()) {
            throw new OAuthException(messages.get("oauth2.error.noVerifiedLogin"));
        }
        save(true);
    }

    @Override
    @Transactional
    public void disable() {
        save(false);
    }

    @Override
    @Transactional
    public void reset() {
        save(false);
    }

    private void save(boolean enabled) {
        OAuthGate gate = gates.find().orElseGet(() -> new OAuthGate(clock.instant()));
        gate.setEnabled(enabled, clock.instant());
        gates.save(gate);
        uiEnabled = enabled;
    }

    @Override
    @Transactional
    public AccessDecision authorize(String issuer, String email, boolean emailVerified, String subject) {
        String normalized = AllowPolicy.normalizeEmail(email);
        AccessDecision decision = decide(issuer, normalized, emailVerified, subject);
        if (decision.allowed()) {
            Instant now = clock.instant();
            OAuthGate gate = gates.find().orElseGet(() -> new OAuthGate(now));
            gate.verified(now);
            gates.save(gate);
        } else {
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
        Optional<AllowedUser> exact = allowed.findByKindAndValue(AllowKind.EMAIL, email);
        if (exact.isPresent()) {
            AllowedUser entry = exact.get();
            if (entry.isBound() && !entry.matchesIdentity(issuer, subject)) {
                return AccessDecision.deny(AccessDecision.Reason.IDENTITY_MISMATCH);
            }
            if (!entry.isBound() && issuer != null && subject != null) {
                entry.bind(issuer, subject);
            }
            entry.loggedIn(clock.instant());
            allowed.save(entry);
            return AccessDecision.allow();
        }
        if (env.allowedEmails().contains(email)) {
            return AccessDecision.allow();
        }
        String domain = AllowPolicy.domainOf(email);
        if (env.allowedDomains().contains(domain) || allowed.findByKindAndValue(AllowKind.DOMAIN, domain).isPresent()) {
            allowed.findByKindAndValue(AllowKind.DOMAIN, domain).ifPresent(entry -> {
                entry.loggedIn(clock.instant());
                allowed.save(entry);
            });
            return AccessDecision.allow();
        }
        return AccessDecision.deny(AccessDecision.Reason.NOT_LISTED);
    }
}
