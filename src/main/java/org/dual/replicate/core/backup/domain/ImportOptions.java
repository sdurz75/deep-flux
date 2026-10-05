package org.dual.replicate.core.backup.domain;

import java.nio.file.Path;

/** {@code replace}: azzera lo schema di destinazione se non e' vergine (cancella tutti i dati attuali), solo su richiesta esplicita. */
public record ImportOptions(Path source, boolean replace) {
}
