package org.dual.hexa.app.generation.application;

import java.util.Map;

import org.dual.hexa.core.tokens.port.in.IApiTokens;
import org.dual.hexa.app.generation.domain.ApiTokenProvider;
import org.springframework.stereotype.Service;

/**
 * Il lato "Replicate" dei token API: le form e la chat scelgono il token per ID ({@link ApiTokenProvider#idParam}) e qui lo
 * si sostituisce col plaintext sotto la chiave dell'input Replicate ({@link ApiTokenProvider#replicateParam}). Le select dei
 * token per le form le monta GenerationFormService. Il CRUD e la cifratura sono del core ({@link IApiTokens}).
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
