package org.dual.hexa.oauth2.login.adapter.in.web;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.dual.hexa.core.config.domain.ConfigChange;
import org.dual.hexa.core.config.domain.ConfigException;
import org.dual.hexa.core.config.domain.ConfigField;
import org.dual.hexa.core.config.domain.ModuleValues;
import org.dual.hexa.core.config.port.in.IConfigModule;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.dual.hexa.oauth2.login.domain.ConfigKeys;
import org.dual.hexa.oauth2.login.domain.OAuthException;
import org.dual.hexa.oauth2.login.port.in.IOAuthAccess;
import org.dual.hexa.oauth2.login.port.in.IOAuthProviders;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Le impostazioni del cancello, nella pagina {@code /settings} del core: interruttore, provider (righe con il segreto del client come {@code SECRET} di tipo
 * {@code OAUTH2_CLIENT}) e gli elenchi degli ammessi (additivi: property e variabili {@code HX_OAUTH2_ALLOWED_*} si sommano alle voci salvate). Lo stato, la
 * checklist e «Prova accesso» stanno nel fragment {@code oauth2-settings :: extra}. Le precondizioni stanno in {@code IOAuthAccess#checkChange} (il cancello
 * fallisce chiuso); {@link IOAuthAccess} e' letto in modo pigro perche' il registro dei moduli di configurazione si costruisce prima del servizio che lo usa.
 */
@Component
@Order(30)
class Oauth2ConfigModule implements IConfigModule {

    private static final Pattern SLUG = Pattern.compile("[a-z][a-z0-9-]{1,19}");
    static final String EMAIL_PATTERN = "[^@\\s,;\"'<>\\\\]+@[a-z0-9]([a-z0-9.-]*[a-z0-9])?\\.[a-z]{2,}";
    static final String DOMAIN_PATTERN = "@?[a-z0-9]([a-z0-9.-]*[a-z0-9])?\\.[a-z]{2,}";

    private final ObjectProvider<IOAuthAccess> access;
    private final ObjectProvider<IOAuthProviders> providers;
    private final Messages messages;

    Oauth2ConfigModule(ObjectProvider<IOAuthAccess> access, ObjectProvider<IOAuthProviders> providers, Messages messages) {
        this.access = access;
        this.providers = providers;
        this.messages = messages;
    }

    @Override
    public String id() {
        return ConfigKeys.MODULE;
    }

    @Override
    public String titleKey() {
        return "oauth2.settings.title";
    }

    @Override
    public List<ConfigField> fields() {
        return List.of(
                ConfigField.bool(ConfigKeys.ENABLED, false, "oauth2.field.enabled", "oauth2.field.enabled.help").withEnvVar("HX_OAUTH2_ENABLED"),
                ConfigField.collection(ConfigKeys.PROVIDERS, "oauth2.field.providers", "oauth2.field.providers.help", List.of(
                        ConfigField.Column.text(ConfigKeys.SLUG, "oauth2.column.slug", SLUG.pattern(), "oauth2.error.slugFormat", true),
                        ConfigField.Column.text(ConfigKeys.TITLE, "oauth2.column.title", null, null, true),
                        ConfigField.Column.text(ConfigKeys.ISSUER, "oauth2.column.issuer", null, null, true),
                        ConfigField.Column.text(ConfigKeys.CLIENT_ID, "oauth2.column.clientId", null, null, true),
                        ConfigField.Column.secret(ConfigKeys.CLIENT_SECRET, "oauth2.column.clientSecret")),
                        ConfigKeys.SLUG, 10, IOAuthProviders.SECRET_TYPE),
                ConfigField.list(ConfigKeys.ALLOWED_EMAILS, "oauth2.field.allowedEmails", "oauth2.field.allowedEmails.help", "HX_OAUTH2_ALLOWED_EMAILS",
                        EMAIL_PATTERN, "oauth2.error.emailFormat", true, true),
                ConfigField.list(ConfigKeys.ALLOWED_DOMAINS, "oauth2.field.allowedDomains", "oauth2.field.allowedDomains.help", "HX_OAUTH2_ALLOWED_DOMAINS",
                        DOMAIN_PATTERN, "oauth2.error.domainFormat", true, true));
    }

    @Override
    public Optional<String> fragment() {
        return Optional.of("fragments/core/oauth2-settings :: extra");
    }

    @Override
    public void validate(ConfigChange change) {
        ModuleValues proposed = change.proposed();
        List<ModuleValues.Row> rows = proposed.getRows(ConfigKeys.PROVIDERS);
        String envSlug = providers.getObject().list().stream().filter(p -> p.fromEnv()).map(p -> p.slug()).findFirst().orElse("");
        for (ModuleValues.Row row : rows) {
            if (row.id().equals(envSlug)) {
                throw new ConfigException(messages.get("oauth2.error.slugTaken", row.id()));
            }
            if (!validIssuer(row.get(ConfigKeys.ISSUER).strip().replaceAll("/+$", ""))) {
                throw new ConfigException(messages.get("oauth2.error.issuerFormat"));
            }
        }
        int allowed = proposed.getList(ConfigKeys.ALLOWED_EMAILS).size() + proposed.getList(ConfigKeys.ALLOWED_DOMAINS).size();
        try {
            access.getObject().checkChange(change.current().getBoolean(ConfigKeys.ENABLED), proposed.getBoolean(ConfigKeys.ENABLED), rows.size(), allowed);
        } catch (OAuthException e) {
            throw new ConfigException(e.getMessage());
        }
    }

    /** https obbligatorio; http solo verso localhost (provider di prova); niente parametri. */
    static boolean validIssuer(String issuer) {
        if (issuer.isEmpty() || issuer.length() > 300) {
            return false;
        }
        try {
            URI uri = URI.create(issuer);
            if (uri.getHost() == null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                return false;
            }
            return "https".equals(uri.getScheme())
                    || ("http".equals(uri.getScheme()) && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost())));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
