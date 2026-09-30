package org.dual.replicate.repository;

import java.time.Instant;
import java.util.Optional;

import org.dual.replicate.domain.AppError;
import org.dual.replicate.domain.AppErrorSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppErrorRepository extends JpaRepository<AppError, Long> {

    /** Serie "aperta" dello stesso errore (generationId null ⇒ IS NULL): vedi AppErrorService#record. */
    Optional<AppError> findFirstBySourceAndOperationAndGenerationIdAndErrorTypeAndLastSeenAtAfterOrderByIdDesc(
            AppErrorSource source, String operation, Long generationId, String errorType, Instant after);

    /** Listato di /errors: ultimo avvistamento prima. */
    Page<AppError> findAllByOrderByLastSeenAtDesc(Pageable pageable);
}
