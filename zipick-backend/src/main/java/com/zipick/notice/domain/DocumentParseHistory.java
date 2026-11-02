package com.zipick.notice.domain;

import com.zipick.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
        name = "document_parse_history",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_document_parse_history_key",
                columnNames = {"document_key", "parser_version"}
        )
)
public class DocumentParseHistory extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "document_parse_history_id")
    private Long id;

    @Column(name = "document_key", nullable = false, length = 700)
    private String documentKey;

    @Column(name = "parser_version", nullable = false, length = 100)
    private String parserVersion;

    @Column(name = "parse_status", nullable = false, length = 30)
    private String parseStatus;

    @Column(name = "parsed_unit_count", nullable = false)
    private int parsedUnitCount;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "parsed_at", nullable = false)
    private Instant parsedAt;

    protected DocumentParseHistory() {
    }

    public DocumentParseHistory(String documentKey, String parserVersion) {
        this.documentKey = documentKey;
        this.parserVersion = parserVersion;
        this.parseStatus = "PENDING";
        this.parsedAt = Instant.now();
    }

    public void markSuccess(Long fileSize, int parsedUnitCount) {
        this.parseStatus = "SUCCESS";
        this.fileSize = fileSize;
        this.parsedUnitCount = parsedUnitCount;
        this.parsedAt = Instant.now();
    }

    public boolean isSuccess() { return "SUCCESS".equals(parseStatus); }
}
