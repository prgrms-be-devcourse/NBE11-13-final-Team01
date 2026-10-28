package com.zipick.notice.repository;

import com.zipick.notice.domain.DocumentParseHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DocumentParseHistoryRepository extends JpaRepository<DocumentParseHistory, Long> {
    Optional<DocumentParseHistory> findByDocumentKeyAndParserVersion(String documentKey, String parserVersion);
    long deleteAllByDocumentKeyStartingWithAndParserVersion(String prefix, String parserVersion);
}
