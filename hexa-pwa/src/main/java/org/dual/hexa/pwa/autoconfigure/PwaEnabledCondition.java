package org.dual.hexa.pwa.autoconfigure;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Decide se hexa-pwa e' attivo: {@code app.pwa.enabled} se presente, altrimenti la variabile d'ambiente {@code HX_PWA_ENABLED}, altrimenti
 * {@code true}. Non e' un placeholder in un yml perche' hexa-pwa non ha file di configurazione da importare.
 */
class PwaEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var env = context.getEnvironment();
        String value = env.getProperty("app.pwa.enabled");
        if (value == null || value.isBlank()) {
            value = env.getProperty("HX_PWA_ENABLED");
        }
        return value == null || value.isBlank() || Boolean.parseBoolean(value.trim());
    }
}
