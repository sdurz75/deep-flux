package org.dual.hexa.app.generation.adapter.out.secrets;

import java.util.Arrays;
import java.util.List;

import org.dual.hexa.app.generation.domain.AppSecretType;
import org.dual.hexa.core.secrets.domain.SecretType;
import org.dual.hexa.core.secrets.port.out.ISecretTypeCatalog;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** I tipi di segreto dell'app (CivitAI, HuggingFace: LoRA privati di flux-dev-lora, upload dei pesi); le etichette sono {@code secrets.type.<NAME>} in {@code messages*}. */
@Component
@Order(0)
public class AppSecretTypes implements ISecretTypeCatalog {

    @Override
    public List<SecretType> types() {
        return Arrays.stream(AppSecretType.values()).map(type -> new SecretType(type.name(), "secrets.type." + type.name())).toList();
    }
}
