package com.zipick.admin.aidoc;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AiDocumentIssueRepository extends JpaRepository<AiDocumentIssue, Long> {

    Optional<AiDocumentIssue> findByDocumentKey(String documentKey);

    void deleteByDocumentKey(String documentKey);

    Page<AiDocumentIssue> findAllByAcknowledgedOrderByLastSeenAtDesc(boolean acknowledged, Pageable pageable);

    Page<AiDocumentIssue> findAllByAcknowledgedAndReasonOrderByLastSeenAtDesc(boolean acknowledged, String reason, Pageable pageable);

    long countByAcknowledgedFalse();
}
