package org.dual.hexa.oauth2.login.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.dual.hexa.core.config.domain.ModuleValues;
import org.dual.hexa.core.config.port.in.IModuleSettings;
import org.dual.hexa.oauth2.login.domain.ConfigKeys;
import org.dual.hexa.oauth2.login.domain.ProviderConfig;
import org.dual.hexa.oauth2.login.port.in.IOAuthProviders;
import org.dual.hexa.oauth2.login.port.out.IOAuthEnvironment;
import org.springframework.stereotype.Service;

/** I provider: quello d'ambiente (virtuale, in sola lettura) e le righe {@code providers} delle impostazioni del modulo ({@code ConfigKeys}). */
@Service
class ProviderCatalog implements IOAuthProviders {

    private final IModuleSettings settings;
    private final IOAuthEnvironment env;

    ProviderCatalog(IModuleSettings settings, IOAuthEnvironment env) {
        this.settings = settings;
        this.env = env;
    }

    @Override
    public List<ProviderConfig> list() {
        List<ProviderConfig> all = new ArrayList<>();
        env.provider().ifPresent(p -> all.add(new ProviderConfig(p.slug(), p.title(), p.issuerUri(), p.clientId(), true)));
        for (ModuleValues.Row row : settings.values(ConfigKeys.MODULE).getRows(ConfigKeys.PROVIDERS)) {
            all.add(new ProviderConfig(row.id(), row.get(ConfigKeys.TITLE), row.get(ConfigKeys.ISSUER).strip().replaceAll("/+$", ""),
                    row.get(ConfigKeys.CLIENT_ID).strip(), false));
        }
        return all;
    }

    @Override
    public Optional<ProviderConfig> find(String slug) {
        return list().stream().filter(p -> p.slug().equals(slug)).findFirst();
    }

    @Override
    public Optional<String> clientSecret(String slug) {
        Optional<ProviderConfig> config = find(slug);
        if (config.isEmpty()) {
            return Optional.empty();
        }
        if (config.get().fromEnv()) {
            return env.provider().map(IOAuthEnvironment.EnvProvider::clientSecret);
        }
        return settings.values(ConfigKeys.MODULE).getRows(ConfigKeys.PROVIDERS).stream().filter(row -> row.id().equals(slug)).findFirst()
                .flatMap(row -> row.secret(ConfigKeys.CLIENT_SECRET));
    }
}
