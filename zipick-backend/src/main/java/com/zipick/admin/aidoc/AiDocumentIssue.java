package com.zipick.admin.aidoc;

import com.zipick.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;

/**
 * Gemini PDF 추출이 미완료(incomplete)·빈 결과(empty)·예외(exception)로 끝난 첨부문서.
 * 지금까지는 `COLLECTOR_METRIC event=ai_document_error` 로그 한 줄뿐이라 운영자가 로그를 뒤져야 했다.
 * 문서 키(documentKey) 하나당 한 행이고, AI 실행마다 다시 실패하면 occurrences 만 올라간다.
 * 성공하면 행을 지운다(자동 해소). 운영자가 "확인함" 으로 표시하면 목록에서 접힌다.
 */
@Getter
@Entity
@Table(name = "ai_document_issue")
public class AiDocumentIssue extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ai_document_issue_id")
    private Long id;

    @Column(name = "document_key", nullable = false, length = 700, unique = true)
    private String documentKey;

    @Column(name = "agency", length = 50)
    private String agency;

    @Column(name = "notice_title", length = 500)
    private String noticeTitle;

    @Column(name = "file_name", length = 500)
    private String fileName;

    /** incomplete / empty / exception (collect-notices-observed.sh 의 reason 라벨과 같은 값) */
    @Column(name = "reason", nullable = false, length = 20)
    private String reason;

    @Column(name = "expected_units")
    private Integer expectedUnits;

    @Column(name = "parsed_units")
    private Integer parsedUnits;

    /** 추출기 경고 또는 예외 메시지 */
    @Column(name = "detail", columnDefinition = "text")
    private String detail;

    @Column(name = "occurrences", nullable = false)
    private int occurrences;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "acknowledged", nullable = false)
    private boolean acknowledged;

    @Column(name = "acknowledged_by")
    private Long acknowledgedBy;

    @Column(name = "admin_note", length = 500)
    private String adminNote;

    protected AiDocumentIssue() {
    }

    public AiDocumentIssue(String documentKey, String agency, String noticeTitle, String fileName) {
        this.documentKey = documentKey;
        this.agency = agency;
        this.noticeTitle = noticeTitle;
        this.fileName = fileName;
        this.occurrences = 0;
        Instant now = Instant.now();
        this.firstSeenAt = now;
        this.lastSeenAt = now;
    }

    public void observe(String reason, Integer expectedUnits, Integer parsedUnits, String detail) {
        this.reason = reason;
        this.expectedUnits = expectedUnits;
        this.parsedUnits = parsedUnits;
        this.detail = detail;
        this.occurrences++;
        this.lastSeenAt = Instant.now();
    }

    public void acknowledge(Long adminId, String note) {
        this.acknowledged = true;
        this.acknowledgedBy = adminId;
        this.adminNote = note;
    }

    public void reopen() {
        this.acknowledged = false;
        this.acknowledgedBy = null;
    }
}
