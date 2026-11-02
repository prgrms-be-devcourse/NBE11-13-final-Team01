package com.zipick.external.ai;

import com.zipick.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/** Lite 추출과 분리해서 보관하는 3.8 Flash 정규화 재시도 작업입니다. */
@Entity
@Table(
        name = "ai_normalization_job",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ai_normalization_job_source_document",
                columnNames = {"source", "document_id"}
        )
)
public class AiNormalizationJob extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ai_normalization_job_id")
    private Long id;

    @Column(name = "source", nullable = false, length = 30)
    private String source;

    @Column(name = "document_id", nullable = false, length = 150)
    private String documentId;

    @Column(name = "notice_title", length = 500)
    private String noticeTitle;

    @Column(name = "input_hash", nullable = false, length = 64)
    private String inputHash;

    @Column(name = "normalizer_version", nullable = false, length = 100)
    private String normalizerVersion;

    @Column(name = "input_payload", nullable = false, columnDefinition = "text")
    private String inputPayload;

    @Enumerated(EnumType.STRING)
    @Column(name = "normalization_status", nullable = false, length = 30)
    private AiNormalizationStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "normalized_at")
    private Instant normalizedAt;

    protected AiNormalizationJob() {
    }

    public AiNormalizationJob(
            String source,
            String documentId,
            String noticeTitle,
            String inputHash,
            String normalizerVersion,
            String inputPayload
    ) {
        this.source = source;
        this.documentId = documentId;
        this.noticeTitle = noticeTitle;
        replaceInput(noticeTitle, inputHash, normalizerVersion, inputPayload);
    }

    /** 입력이나 정규화 버전이 바뀐 경우에만 완료 상태를 다시 대기로 되돌립니다. */
    public boolean enqueue(
            String noticeTitle,
            String newInputHash,
            String newNormalizerVersion,
            String newInputPayload
    ) {
        boolean changed = !inputHash.equals(newInputHash) || !normalizerVersion.equals(newNormalizerVersion);
        this.noticeTitle = noticeTitle;
        this.inputPayload = newInputPayload;
        if (changed) {
            replaceInput(noticeTitle, newInputHash, newNormalizerVersion, newInputPayload);
        }
        return changed;
    }

    public void markSuccess(Instant now) {
        this.status = AiNormalizationStatus.SUCCESS;
        this.normalizedAt = now;
        this.nextRetryAt = null;
        this.lastError = null;
    }

    public void markRetry(String error, Instant now) {
        this.status = AiNormalizationStatus.RETRY;
        this.attemptCount++;
        long delayMinutes = Math.min(24L * 60L, 15L * (1L << Math.min(attemptCount - 1, 6)));
        this.nextRetryAt = now.plusSeconds(delayMinutes * 60L);
        this.lastError = abbreviate(error, 4000);
    }

    public void requeueForNormalizerVersion(String newNormalizerVersion) {
        if (normalizerVersion.equals(newNormalizerVersion)) return;
        replaceInput(noticeTitle, inputHash, newNormalizerVersion, inputPayload);
    }

    private void replaceInput(
            String noticeTitle,
            String inputHash,
            String normalizerVersion,
            String inputPayload
    ) {
        this.noticeTitle = noticeTitle;
        this.inputHash = inputHash;
        this.normalizerVersion = normalizerVersion;
        this.inputPayload = inputPayload;
        this.status = AiNormalizationStatus.PENDING;
        this.attemptCount = 0;
        this.nextRetryAt = null;
        this.lastError = null;
        this.normalizedAt = null;
    }

    private String abbreviate(String value, int maximum) {
        if (value == null || value.length() <= maximum) return value;
        return value.substring(0, maximum);
    }

    public Long getId() { return id; }
    public String getSource() { return source; }
    public String getDocumentId() { return documentId; }
    public String getNoticeTitle() { return noticeTitle; }
    public String getInputHash() { return inputHash; }
    public String getNormalizerVersion() { return normalizerVersion; }
    public String getInputPayload() { return inputPayload; }
    public AiNormalizationStatus getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }
    public Instant getNextRetryAt() { return nextRetryAt; }
    public String getLastError() { return lastError; }
    public Instant getNormalizedAt() { return normalizedAt; }
}
