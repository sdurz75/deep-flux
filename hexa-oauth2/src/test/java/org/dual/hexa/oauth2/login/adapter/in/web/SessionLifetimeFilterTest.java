package org.dual.hexa.oauth2.login.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.dual.hexa.core.config.domain.ModuleValues;
import org.dual.hexa.core.config.port.in.IModuleSettings;
import org.dual.hexa.oauth2.login.domain.ConfigKeys;
import org.dual.hexa.oauth2.login.port.in.IOAuthAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class SessionLifetimeFilterTest {

    private final IOAuthAccess access = mock(IOAuthAccess.class);
    private final IModuleSettings settings = mock(IModuleSettings.class);
    private final SessionLifetimeFilter filter = new SessionLifetimeFilter(access, settings);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void values(int days, int maxDays, boolean remember) {
        Map<String, String> raw = Map.of(ConfigKeys.SESSION_DAYS, "" + days, ConfigKeys.SESSION_MAX_DAYS, "" + maxDays,
                ConfigKeys.SESSION_REMEMBER, "" + remember);
        when(settings.values(ConfigKeys.MODULE)).thenReturn(new ModuleValues(raw::get, key -> null));
    }

    private MockHttpServletResponse run(MockHttpSession session, boolean enabled, boolean authenticated) throws Exception {
        when(access.isEnabled()).thenReturn(enabled);
        if (authenticated) {
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("u", "p", "ROLE_USER"));
        }
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void setsTheInactivityIntervalAndAPersistentCookieOnlyOncePerHour() throws Exception {
        values(10, 0, true);
        MockHttpSession session = new MockHttpSession();
        MockHttpServletResponse first = run(session, true, true);
        assertThat(session.getMaxInactiveInterval()).isEqualTo(10 * 86400);
        assertThat(first.getHeader("Set-Cookie")).contains("Max-Age=864000").contains("HttpOnly");
        assertThat(run(session, true, true).getHeader("Set-Cookie")).isNull();
    }

    @Test
    void withoutRememberNoCookieIsEmitted() throws Exception {
        values(10, 0, false);
        assertThat(run(new MockHttpSession(), true, true).getHeader("Set-Cookie")).isNull();
    }

    @Test
    void theAbsoluteCapInvalidatesTheSession() throws Exception {
        values(1, 2, true);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionLifetimeFilter.LOGIN_AT, System.currentTimeMillis() - 3 * 86_400_000L);
        run(session, true, true);
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void gateOffOrAnonymousChangesNothing() throws Exception {
        values(10, 0, true);
        MockHttpSession off = new MockHttpSession();
        assertThat(run(off, false, true).getHeader("Set-Cookie")).isNull();
        MockHttpSession anonymous = new MockHttpSession();
        SecurityContextHolder.clearContext();
        assertThat(run(anonymous, true, false).getHeader("Set-Cookie")).isNull();
        assertThat(anonymous.getMaxInactiveInterval()).isNotEqualTo(10 * 86400);
    }
}
