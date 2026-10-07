package org.hexa.app.shared.domain;

import java.util.Map;
import java.util.Set;

/**
 * Kernel dell'app per i campi di una form: la lettura tollerante dei valori sottomessi (sempre stringhe, sia da un submit HTML sia
 * dal JSON del pannello della chat, che le converte a stringa). Un campo assente, vuoto o non valido diventa {@code null} (mai
 * un'eccezione) e {@link #putIfPresent} lo lascia fuori dalla mappa dei parametri: vale il default del provider. Puro JDK: lo
 * usano gli handler dei parametri di {@code generation} e qualunque altra feature che legga campi di form.
 */
public final class FormFields {

    private FormFields() {
    }

    /** Aggiunge {@code key} solo se {@code value} non e' {@code null}. */
    public static void putIfPresent(Map<String, Object> params, String key, Object value) {
        if (value != null) {
            params.put(key, value);
        }
    }

    /**
     * {@code value} solo se e' uno dei valori ammessi, altrimenti {@code null} (la chiave viene omessa e vale il default del
     * provider): un valore residuo di un altro form-type (es. il {@code aspect_ratio=custom} di FLUX_LORA_FINETUNE) non deve arrivare
     * al provider come 422.
     */
    public static String asOneOf(String value, Set<String> allowed) {
        return value != null && allowed.contains(value) ? value : null;
    }

    /** Testo ripulito, {@code null} se assente o vuoto. */
    public static String asText(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public static Integer asInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Long asLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Double asDouble(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Una checkbox HTML deselezionata non sottomette affatto la propria chiave: e' "spuntata" solo se la chiave c'e'. */
    public static boolean isChecked(Map<String, String> submittedFields, String key) {
        return submittedFields.containsKey(key);
    }
}
