package org.hexa.app.generation.adapter.out.tokens;

import java.util.Arrays;
import java.util.List;

import org.hexa.core.tokens.port.out.ITokenProviderCatalog;
import org.hexa.app.generation.domain.ApiTokenProvider;
import org.springframework.stereotype.Component;

/** I provider di token dell'app (CivitAI, HuggingFace: LoRA privati di flux-dev-lora). */
@Component
public class AppTokenProviders implements ITokenProviderCatalog {

    @Override
    public List<String> providers() {
        return Arrays.stream(ApiTokenProvider.values()).map(Enum::name).toList();
    }
}
