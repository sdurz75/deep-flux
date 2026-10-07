package org.dual.hexa.core.backup.domain;

import java.util.regex.Pattern;

/**
 * Una colonna del DB che contiene il nome di un binario dello storage (es. {@code generation_image.filename}): e' come l'app dice al backup
 * quali file esistono, perche' il backend non ha un elenco ({@code IBlobReferences}). Gli identificatori finiscono in una query, quindi sono
 * solo minuscole, cifre e underscore.
 */
public record BlobColumn(String table, String column) {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]*");

    public BlobColumn {
        if (table == null || !IDENTIFIER.matcher(table).matches() || column == null || !IDENTIFIER.matcher(column).matches()) {
            throw new IllegalArgumentException("Identificatore non valido: " + table + "." + column);
        }
    }
}
