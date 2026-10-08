package org.dual.hexa.pwa.shell.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.dual.hexa.core.config.domain.ModuleValues;
import org.dual.hexa.core.config.port.in.IModuleSettings;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.dual.hexa.core.web.BuildInfo;
import org.junit.jupiter.api.Test;

/** Composizione di manifest e worker, senza Spring: sostituzione dei segnaposto e nome di cache legato alla build. */
class PwaServiceTest {

    private static PwaService service(String time, String commit) {
        Messages messages = mock(Messages.class);
        when(messages.get("app.title")).thenReturn("Titolo");
        when(messages.get("app.brand")).thenReturn("Marchio");
        BuildInfo build = mock(BuildInfo.class);
        when(build.getTime()).thenReturn(time);
        when(build.getCommit()).thenReturn(commit);
        IModuleSettings settings = mock(IModuleSettings.class);
        Map<String, String> values = Map.of("theme-color", "#fff", "theme-color-dark", "#000", "display", "standalone");
        when(settings.values("pwa")).thenReturn(new ModuleValues(values::get, key -> {
            throw new IllegalStateException(key);
        }));
        return new PwaService(() -> "scope=__SCOPE__ offline=__OFFLINE_URL__ cache=__CACHE_NAME__", messages, build, settings);
    }

    @Test
    void theWorkerGetsScopeOfflineUrlAndABuildBoundCacheName() {
        String worker = service("07/10/2026 15:38", "abc123-dirty").serviceWorker("/app");

        assertThat(worker).isEqualTo("scope=/app/ offline=/app/offline cache=hexa-shell-071020261538abc123dirty");
    }

    @Test
    void anUnknownBuildFallsBackToAFixedCacheName() {
        assertThat(service(null, null).serviceWorker("")).contains("cache=hexa-shell-dev").contains("scope=/ ");
    }

    @Test
    void theManifestLivesUnderTheContextPath() {
        var manifest = service(null, null).manifest("/app");

        assertThat(manifest.startUrl()).isEqualTo("/app/");
        assertThat(manifest.name()).isEqualTo("Titolo");
        assertThat(manifest.shortName()).isEqualTo("Marchio");
        assertThat(manifest.icons()).extracting(i -> i.src()).allMatch(src -> src.startsWith("/app/pwa/icons/"));
        assertThat(manifest.icons()).extracting(i -> i.purpose()).contains("maskable");
    }
}
