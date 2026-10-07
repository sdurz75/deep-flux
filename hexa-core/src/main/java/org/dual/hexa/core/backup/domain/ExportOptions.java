package org.dual.hexa.core.backup.domain;

import java.nio.file.Path;

/** {@code encrypt=false} solo su richiesta esplicita ({@code --no-encrypt}): l'archivio resta uno zip leggibile con prompt, chat e immagini. */
public record ExportOptions(Path target, boolean encrypt) {
}
