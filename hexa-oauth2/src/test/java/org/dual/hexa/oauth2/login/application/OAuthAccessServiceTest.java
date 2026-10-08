package org.dual.hexa.oauth2.login.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.dual.hexa.core.events.port.in.ISystemEvents;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.dual.hexa.oauth2.login.domain.AccessDecision;
import org.dual.hexa.oauth2.login.domain.AllowKind;
import org.dual.hexa.oauth2.login.domain.AllowedUser;
import org.dual.hexa.oauth2.login.domain.OAuthException;
import org.dual.hexa.oauth2.login.domain.OAuthGate;
import org.dual.hexa.oauth2.login.port.in.IOAuthAccess;
import org.dual.hexa.oauth2.login.port.out.IAllowedUserStore;
import org.dual.hexa.oauth2.login.port.out.IGateStore;
import org.dual.hexa.oauth2.login.port.out.IOAuthEnvironment;
import org.dual.hexa.oauth2.login.port.out.IProviderStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

/** La decisione di accesso e il cancello, senza Spring: e' qui che si fissa chi entra e chi no. */
class OAuthAccessServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");

    private final IGateStore gates = mock(IGateStore.class);
    private final IAllowedUserStore allowed = mock(IAllowedUserStore.class);
    private final IProviderStore providers = mock(IProviderStore.class);
    private final IOAuthEnvironment env = mock(IOAuthEnvironment.class);
    private final ISystemEvents events = mock(ISystemEvents.class);
    private OAuthAccessService service;

    @BeforeEach
    void setUp() {
        StaticMessageSource source = new StaticMessageSource();
        source.setUseCodeAsDefaultMessage(true);
        when(env.allowedEmails()).thenReturn(List.of());
        when(env.allowedDomains()).thenReturn(List.of());
        when(env.provider()).thenReturn(Optional.empty());
        when(gates.find()).thenReturn(Optional.empty());
        when(allowed.findByKindAndValue(any(), any())).thenReturn(Optional.empty());
        service = new OAuthAccessService(gates, allowed, providers, env, events, new Messages(source), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void anUnverifiedOrMissingEmailNeverEntersEvenIfListed() {
        when(allowed.findByKindAndValue(AllowKind.EMAIL, "a@example.com")).thenReturn(Optional.of(new AllowedUser(AllowKind.EMAIL, "a@example.com", NOW)));

        assertThat(service.authorize("iss", "a@example.com", false, "s").reason()).isEqualTo(AccessDecision.Reason.EMAIL_UNVERIFIED);
        assertThat(service.authorize("iss", null, true, "s").reason()).isEqualTo(AccessDecision.Reason.EMAIL_MISSING);
        verify(allowed, never()).save(any());
    }

    @Test
    void aListedEmailEntersCaseInsensitivelyAndBindsTheFirstIdentity() {
        AllowedUser entry = new AllowedUser(AllowKind.EMAIL, "a@example.com", NOW);
        when(allowed.findByKindAndValue(AllowKind.EMAIL, "a@example.com")).thenReturn(Optional.of(entry));

        assertThat(service.authorize("iss", " A@Example.com ", true, "sub-1").allowed()).isTrue();

        assertThat(entry.isBound()).isTrue();
        assertThat(entry.matchesIdentity("iss", "sub-1")).isTrue();
        assertThat(entry.getLastLoginAt()).isEqualTo(NOW);
    }

    @Test
    void aBoundEmailRefusesAnotherSubjectOrIssuer() {
        AllowedUser entry = new AllowedUser(AllowKind.EMAIL, "a@example.com", NOW);
        entry.bind("iss", "sub-1");
        when(allowed.findByKindAndValue(AllowKind.EMAIL, "a@example.com")).thenReturn(Optional.of(entry));

        assertThat(service.authorize("iss", "a@example.com", true, "sub-2").reason()).isEqualTo(AccessDecision.Reason.IDENTITY_MISMATCH);
        assertThat(service.authorize("other", "a@example.com", true, "sub-1").reason()).isEqualTo(AccessDecision.Reason.IDENTITY_MISMATCH);
        assertThat(service.authorize("iss", "a@example.com", true, "sub-1").allowed()).isTrue();
    }

    @Test
    void aDomainMatchesOnlyTheExactDomain() {
        when(allowed.findByKindAndValue(AllowKind.DOMAIN, "example.com")).thenReturn(Optional.of(new AllowedUser(AllowKind.DOMAIN, "example.com", NOW)));

        assertThat(service.authorize("iss", "x@example.com", true, "s").allowed()).isTrue();
        assertThat(service.authorize("iss", "x@evilexample.com", true, "s").reason()).isEqualTo(AccessDecision.Reason.NOT_LISTED);
        assertThat(service.authorize("iss", "x@sub.example.com", true, "s").reason()).isEqualTo(AccessDecision.Reason.NOT_LISTED);
    }

    @Test
    void environmentEntriesAreAddedToTheSavedOnes() {
        when(env.allowedEmails()).thenReturn(List.of("env@example.com"));
        when(env.allowedDomains()).thenReturn(List.of("corp.example"));

        assertThat(service.authorize("iss", "env@example.com", true, "s").allowed()).isTrue();
        assertThat(service.authorize("iss", "anyone@corp.example", true, "s").allowed()).isTrue();
        assertThat(service.authorize("iss", "x@example.com", true, "s").allowed()).isFalse();
    }

    @Test
    void aSuccessfulLoginIsRecordedAsTheTestLoginAndADeniedOneIsNotedInTheEvents() {
        when(env.allowedEmails()).thenReturn(List.of("env@example.com"));

        service.authorize("iss", "nobody@example.com", true, "s");
        verify(events).warn(any(), any(), any(), any());
        verify(gates, never()).save(any());

        service.authorize("iss", "env@example.com", true, "s");
        verify(gates).save(any(OAuthGate.class));
    }

    @Test
    void theGateCannotBeTurnedOnUntilProviderAllowedUserAndTestLoginExist() {
        assertThatThrownBy(service::enable).isInstanceOf(OAuthException.class).hasMessage("oauth2.error.noProvider");

        when(providers.count()).thenReturn(1L);
        assertThatThrownBy(service::enable).hasMessage("oauth2.error.noAllowed");

        when(env.allowedEmails()).thenReturn(List.of("env@example.com"));
        assertThatThrownBy(service::enable).hasMessage("oauth2.error.noVerifiedLogin");

        OAuthGate gate = new OAuthGate(NOW);
        gate.verified(NOW);
        when(gates.find()).thenReturn(Optional.of(gate));
        service.enable();
        assertThat(service.isEnabled()).isTrue();
        assertThat(gate.isEnabled()).isTrue();
    }

    @Test
    void theResetVariableKeepsTheGateOffWhateverElseSaysAndTheEnvironmentCanTurnItOn() {
        when(env.gateEnabled()).thenReturn(true);
        assertThat(service.isEnabled()).isTrue();

        when(env.reset()).thenReturn(true);
        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    void turningTheGateOffPersistsAndIsVisibleAtOnce() {
        OAuthGate gate = new OAuthGate(NOW);
        gate.setEnabled(true, NOW);
        when(gates.find()).thenReturn(Optional.of(gate));
        assertThat(service.isEnabled()).isTrue();

        service.disable();

        assertThat(service.isEnabled()).isFalse();
        assertThat(gate.isEnabled()).isFalse();
    }

    @Test
    void theStatusMapsWhatIsMissing() {
        var status = service.status();

        assertThat(status.providers()).isZero();
        assertThat(status.allowed()).isZero();
        assertThat(status.canEnable()).isFalse();
    }
}
