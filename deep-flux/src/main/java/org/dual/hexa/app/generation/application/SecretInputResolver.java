package org.dual.hexa.app.generation.application;

import java.util.Map;

import org.dual.hexa.core.secrets.port.in.ISecrets;
import org.dual.hexa.app.generation.domain.AppSecretType;
import org.springframework.stereotype.Service;

/**
 * Il lato "Replicate" dei token API: le form e la chat scelgono il token per ID ({@link AppSecretType#idParam}) e qui lo
 * si sostituisce col plaintext sotto la chiave dell'input Replicate ({@link AppSecretType#replicateParam}). Le select dei
 * token per le form le monta GenerationFormService. Il CRUD e la cifratura sono del core ({@link ISecrets}).
 */
@Service
public class SecretInputResolver {

    private final ISecrets secrets;

    public SecretInputResolver(ISecrets secrets) {
        this.secrets = secrets;
    }

    /**
     * Sostituisce in {@code input} gli ID scelti ({@link AppSecretType#idParam}) col token in chiaro sotto la chiave
     * Replicate ({@link AppSecretType#replicateParam}). Gli ID vengono sempre tolti dall'input (Replicate non li conosce).
     */
    public void resolveInto(Map<String, Object> input) {
        for (AppSecretType provider : AppSecretType.values()) {
            Object chosen = input.remove(provider.idParam());
            Long id = asId(chosen);
            if (id != null) {
                input.put(provider.replicateParam(), secrets.resolve(id, provider.name()));
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
