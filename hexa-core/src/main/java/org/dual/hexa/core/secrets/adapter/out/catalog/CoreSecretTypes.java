package org.dual.hexa.core.secrets.adapter.out.catalog;

import java.util.List;

import org.dual.hexa.core.secrets.domain.SecretType;
import org.dual.hexa.core.secrets.port.out.ISecretTypeCatalog;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** I tipi di default del core: sempre disponibili, in coda a quelli dell'app e dei moduli. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class CoreSecretTypes implements ISecretTypeCatalog {

    @Override
    public List<SecretType> types() {
        return List.of(
                new SecretType("API_TOKEN", "secrets.type.API_TOKEN"),
                new SecretType("PASSWORD", "secrets.type.PASSWORD"),
                new SecretType("GENERIC", "secrets.type.GENERIC"));
    }
}
