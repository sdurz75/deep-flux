package org.dual.replicate.domain;

import org.dual.replicate.core.tokens.domain.ApiToken;

/**
 * Servizio a cui appartiene un token API salvato ({@link ApiToken}). {@code replicateParam} e' la chiave dell'input Replicate
 * che porta il token in chiaro (flux-dev-lora); {@code idParam} e' il campo del form/chat che porta invece l'ID del token
 * scelto per nome (il token in chiaro non attraversa mai il browser dopo il salvataggio).
 */
public enum ApiTokenProvider {
    CIVITAI("civitai_api_token", "civitai_token_id"),
    HUGGINGFACE("hf_api_token", "hf_token_id");

    private final String replicateParam;
    private final String idParam;

    ApiTokenProvider(String replicateParam, String idParam) {
        this.replicateParam = replicateParam;
        this.idParam = idParam;
    }

    public String replicateParam() {
        return replicateParam;
    }

    public String idParam() {
        return idParam;
    }
}
