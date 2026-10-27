package com.zipick.external.ai;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AiNormalizationJobRepository extends JpaRepository<AiNormalizationJob, Long> {

    Optional<AiNormalizationJob> findBySourceAndDocumentId(String source, String documentId);

    List<AiNormalizationJob> findAllByNormalizerVersionNot(String normalizerVersion);

    @Query("""
            select job
            from AiNormalizationJob job
            where job.status in :statuses
              and (job.nextRetryAt is null or job.nextRetryAt <= :now)
            order by job.id
            """)
    List<AiNormalizationJob> findDue(Collection<AiNormalizationStatus> statuses, Instant now);
}
