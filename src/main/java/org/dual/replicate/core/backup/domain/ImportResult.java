package org.dual.replicate.core.backup.domain;

import java.util.List;

/** {@code blobsSkipped}: file gia' presenti nello storage di destinazione (un import interrotto si puo' rilanciare). */
public record ImportResult(int tables, long rows, long blobsRestored, long blobsSkipped, List<String> missingBlobs,
                           int undecryptableTokens) {
}
