package com.zipick.external.ai;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AiNormalizationJobRepository extends JpaRepository<AiNormalizationJob, Long> {

    Optional<AiNormalizationJob> findBySourceAndDocumentId(String source, String documentId);

    /** 운영자 화면 */
    org.springframework.data.domain.Page<AiNormalizationJob> findAllByStatus(AiNormalizationStatus status, org.springframework.data.domain.Pageable pageable);

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
