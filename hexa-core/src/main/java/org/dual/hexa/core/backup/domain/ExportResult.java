package org.dual.hexa.core.backup.domain;

import java.util.List;

public record ExportResult(int tables, long rows, long blobs, long blobBytes, List<String> missingBlobs, boolean encrypted) {
}
