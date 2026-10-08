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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.dual.hexa.core.config.domain.ConfigField;
import org.dual.hexa.core.config.domain.ModuleConfigChangedEvent;
import org.dual.hexa.core.config.domain.ModuleValues;
import org.dual.hexa.core.config.port.in.IModuleSettings;
import org.dual.hexa.core.events.port.in.ISystemEvents;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.dual.hexa.oauth2.login.domain.AccessDecision;
import org.dual.hexa.oauth2.login.domain.OAuthException;
import org.dual.hexa.oauth2.login.domain.OAuthLogin;
import org.dual.hexa.oauth2.login.port.out.ILoginStore;
import org.dual.hexa.oauth2.login.port.out.IOAuthEnvironment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

/** La decisione di accesso e il cancello, senza Spring: e' qui che si fissa chi entra e chi no. */
class OAuthAccessServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");

    private final IModuleSettings settings = mock(IModuleSettings.class);
    private final ILoginStore logins = mock(ILoginStore.class);
    private final IOAuthEnvironment env = mock(IOAuthEnvironment.class);
    private final ISystemEvents events = mock(ISystemEvents.class);
    private final Map<String, String> scalars = new HashMap<>();
    private final Map<String, List<String>> lists = new HashMap<>();
    private final List<ModuleValues.RowData> providerRows = new ArrayList<>();
    private final Map<String, OAuthLogin> stored = new HashMap<>();
    private OAuthAccessService service;

    @BeforeEach
    void setUp() {
        StaticMessageSource source = new StaticMessageSource();
        source.setUseCodeAsDefaultMessage(true);
        when(env.provider()).thenReturn(Optional.empty());
        when(settings.values("oauth2")).thenAnswer(call -> values());
        when(logins.find(any())).thenAnswer(call -> Optional.ofNullable(stored.get(call.<String>getArgument(0))));
        when(logins.save(any())).thenAnswer(call -> {
            OAuthLogin login = call.getArgument(0);
            stored.put(login.getEmail(), login);
            return login;
        });
        when(logins.count()).thenAnswer(call -> (long) stored.size());
        service = new OAuthAccessService(settings, logins, env, events, new Messages(source), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ModuleValues values() {
        ConfigField any = ConfigField.text("x", "", "l", null);
        return new ModuleValues(scalars::get, key -> any, new ModuleValues.Extras() {
            @Override
            public List<String> list(String key) {
                return lists.getOrDefault(key, List.of());
            }

            @Override
            public Optional<String> secret(String key, String rowId, String column) {
                return Optional.empty();
            }

            @Override
            public Optional<String> secretHint(String key, String rowId, String column) {
                return Optional.empty();
            }

            @Override
            public List<ModuleValues.RowData> rows(String key) {
                return providerRows;
            }
        });
    }

    private void allow(String... emails) {
        lists.put("allowed-emails", List.of(emails));
    }

    @Test
    void anUnverifiedOrMissingEmailNeverEntersEvenIfListed() {
        allow("a@example.com");

        assertThat(service.authorize("iss", "a@example.com", false, "s").reason()).isEqualTo(AccessDecision.Reason.EMAIL_UNVERIFIED);
        assertThat(service.authorize("iss", null, true, "s").reason()).isEqualTo(AccessDecision.Reason.EMAIL_MISSING);
        verify(logins, never()).save(any());
    }

    @Test
    void aListedEmailEntersCaseInsensitivelyAndBindsTheFirstIdentity() {
        allow("a@example.com");

        assertThat(service.authorize("iss", " A@Example.com ", true, "sub-1").allowed()).isTrue();

        assertThat(stored.get("a@example.com").matches("iss", "sub-1")).isTrue();
        assertThat(stored.get("a@example.com").getLastLoginAt()).isEqualTo(NOW);
    }

    @Test
    void aBoundEmailRefusesAnotherSubjectOrIssuer() {
        allow("a@example.com");
        service.authorize("iss", "a@example.com", true, "sub-1");

        assertThat(service.authorize("iss", "a@example.com", true, "sub-2").reason()).isEqualTo(AccessDecision.Reason.IDENTITY_MISMATCH);
        assertThat(service.authorize("other", "a@example.com", true, "sub-1").reason()).isEqualTo(AccessDecision.Reason.IDENTITY_MISMATCH);
        assertThat(service.authorize("iss", "a@example.com", true, "sub-1").allowed()).isTrue();
    }

    @Test
    void aDomainMatchesOnlyTheExactDomain() {
        lists.put("allowed-domains", List.of("example.com"));

        assertThat(service.authorize("iss", "x@example.com", true, "s").allowed()).isTrue();
        assertThat(service.authorize("iss", "x@evilexample.com", true, "s").reason()).isEqualTo(AccessDecision.Reason.NOT_LISTED);
        assertThat(service.authorize("iss", "x@sub.example.com", true, "s").reason()).isEqualTo(AccessDecision.Reason.NOT_LISTED);
    }

    @Test
    void aDomainEntryTypedWithAnAtSignStillMatchesAndDomainMembersAreNotBound() {
        lists.put("allowed-domains", List.of("@corp.example"));

        assertThat(service.authorize("iss", "x@corp.example", true, "s1").allowed()).isTrue();
        assertThat(service.authorize("iss", "x@corp.example", true, "s2").allowed()).isTrue();
    }

    @Test
    void aDeniedLoginIsNotedInTheEventsAndASuccessfulOneIsTheTestLogin() {
        allow("env@example.com");

        service.authorize("iss", "nobody@example.com", true, "s");
        verify(events).warn(any(), any(), any(), any());
        assertThat(stored).isEmpty();

        service.authorize("iss", "env@example.com", true, "s");
        assertThat(stored).containsKey("env@example.com");
        assertThat(service.status().verifiedLogin()).isTrue();
    }

    @Test
    void theGateCannotBeTurnedOnUntilProviderAllowedUserAndTestLoginExist() {
        assertThatThrownBy(() -> service.checkChange(false, true, 0, 1)).isInstanceOf(OAuthException.class).hasMessage("oauth2.error.noProvider");
        assertThatThrownBy(() -> service.checkChange(false, true, 1, 0)).hasMessage("oauth2.error.noAllowed");
        assertThatThrownBy(() -> service.checkChange(false, true, 1, 1)).hasMessage("oauth2.error.noVerifiedLogin");

        allow("a@example.com");
        service.authorize("iss", "a@example.com", true, "s");
        service.checkChange(false, true, 1, 1); // nessuna eccezione
    }

    @Test
    void withTheGateOnTheLastProviderOrAllowedEntryCannotBeRemovedButTurningOffIsFree() {
        assertThatThrownBy(() -> service.checkChange(true, true, 0, 1)).hasMessage("oauth2.error.lastProvider");
        assertThatThrownBy(() -> service.checkChange(true, true, 1, 0)).hasMessage("oauth2.error.lastAllowed");
        service.checkChange(true, false, 0, 0);
    }

    @Test
    void theEnvironmentProviderCountsAndAnEnvironmentEnabledGateCannotBeTurnedOffFromTheUi() {
        when(env.provider()).thenReturn(Optional.of(new IOAuthEnvironment.EnvProvider("sso", "SSO", "https://i", "c", "s")));
        allow("a@example.com");
        service.authorize("iss", "a@example.com", true, "s");
        service.checkChange(false, true, 0, 1); // il provider d'ambiente basta

        when(env.gateEnabled()).thenReturn(true);
        assertThatThrownBy(() -> service.checkChange(true, false, 0, 1)).hasMessage("oauth2.error.enabledByEnv");
        when(env.reset()).thenReturn(true);
        service.checkChange(true, false, 0, 1); // con il reset il recupero e' libero
    }

    @Test
    void theResetVariableKeepsTheGateOffWhateverElseSaysAndTheEnvironmentCanTurnItOn() {
        when(env.gateEnabled()).thenReturn(true);
        assertThat(service.isEnabled()).isTrue();

        when(env.reset()).thenReturn(true);
        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    void theSwitchComesFromTheSettingsAndIsReloadedAfterTheyChange() {
        scalars.put("enabled", "true");
        assertThat(service.isEnabled()).isTrue();

        scalars.put("enabled", "false");
        assertThat(service.isEnabled()).isTrue(); // in memoria fino a una notifica
        service.onSettingsChanged(new ModuleConfigChangedEvent("other", Set.of("enabled")));
        assertThat(service.isEnabled()).isTrue();
        service.onSettingsChanged(new ModuleConfigChangedEvent("oauth2", Set.of("enabled")));
        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    void changingTheProvidersInvalidatesTheTestLogins() {
        service.onSettingsChanged(new ModuleConfigChangedEvent("oauth2", Set.of("allowed-emails")));
        verify(logins, never()).deleteAll();

        service.onSettingsChanged(new ModuleConfigChangedEvent("oauth2", Set.of("providers")));
        verify(logins).deleteAll();
    }

    @Test
    void theStatusMapsWhatIsMissing() {
        var status = service.status();

        assertThat(status.providers()).isZero();
        assertThat(status.allowed()).isZero();
        assertThat(status.canEnable()).isFalse();

        providerRows.add(new ModuleValues.RowData("google", Map.of("slug", "google")));
        allow("a@example.com");
        assertThat(service.status().providers()).isEqualTo(1);
        assertThat(service.status().allowed()).isEqualTo(1);
    }

    @Test
    void resetTurnsTheConfiguredSwitchOffThroughTheSettings() {
        scalars.put("enabled", "true");

        service.reset();

        verify(settings).save("oauth2", Map.of("enabled", "false"));
    }
}
