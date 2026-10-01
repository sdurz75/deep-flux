package org.dual.replicate.app;

import java.util.List;
import java.util.Map;

import org.dual.replicate.core.tokens.port.in.IApiTokens;
import org.dual.replicate.domain.ApiTokenProvider;
import org.springframework.stereotype.Service;

/**
 * Il lato "Replicate" dei token API: le form e la chat scelgono il token per ID ({@link ApiTokenProvider#idParam}) e qui lo
 * si sostituisce col plaintext sotto la chiave dell'input Replicate ({@link ApiTokenProvider#replicateParam}); piu' le select
 * dei token per le form dei modelli che li usano. Il CRUD e la cifratura sono del core ({@link IApiTokens}).
 */
@Service
public class TokenInputResolver {

    private final IApiTokens tokens;

    public TokenInputResolver(IApiTokens tokens) {
        this.tokens = tokens;
    }

    /**
     * Sostituisce in {@code input} gli ID scelti ({@link ApiTokenProvider#idParam}) col token in chiaro sotto la chiave
     * Replicate ({@link ApiTokenProvider#replicateParam}). Gli ID vengono sempre tolti dall'input (Replicate non li conosce).
     */
    public void resolveInto(Map<String, Object> input) {
        for (ApiTokenProvider provider : ApiTokenProvider.values()) {
            Object chosen = input.remove(provider.idParam());
            Long id = asId(chosen);
            if (id != null) {
                input.put(provider.replicateParam(), tokens.resolve(id, provider.name()));
            }
        }
    }

    /**
     * Attributi di Model per le select di token delle form di generazione ({@code hfTokens}, {@code civitaiTokens}): solo
     * dove si renderizza il fragment dei parametri di un modello che li usa (FLUX_DEV_LORA), mai a ogni richiesta.
     */
    public Map<String, List<IApiTokens.TokenView>> formOptions() {
        return Map.of("hfTokens", tokens.options(ApiTokenProvider.HUGGINGFACE.name()),
                "civitaiTokens", tokens.options(ApiTokenProvider.CIVITAI.name()));
    }

    /** L'ID arriva da JSON (numero) o da una form (stringa): vuoto/non valido = nessun token scelto. */
    private static Long asId(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.valueOf(s.strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
