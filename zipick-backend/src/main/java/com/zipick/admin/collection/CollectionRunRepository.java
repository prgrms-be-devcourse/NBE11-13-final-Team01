package com.zipick.admin.collection;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CollectionRunRepository extends JpaRepository<CollectionRun, Long> {

    Optional<CollectionRun> findFirstByJobTypeOrderByStartedAtDesc(CollectionJobType jobType);

    Page<CollectionRun> findAllByOrderByStartedAtDesc(Pageable pageable);

    Page<CollectionRun> findAllByJobTypeOrderByStartedAtDesc(CollectionJobType jobType, Pageable pageable);

    List<CollectionRun> findAllByStatus(CollectionRun.Status status);

    List<CollectionRun> findAllByStartedAtAfterOrderByStartedAtDesc(Instant since);
}
