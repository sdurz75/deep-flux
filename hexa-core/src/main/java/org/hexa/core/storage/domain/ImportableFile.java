package org.hexa.core.storage.domain;

import java.nio.file.Path;

/** Un file locale da importare: il nome dello storage, la dimensione in chiaro e dove leggerlo. */
public record ImportableFile(String filename, long size, Path path) {
}
