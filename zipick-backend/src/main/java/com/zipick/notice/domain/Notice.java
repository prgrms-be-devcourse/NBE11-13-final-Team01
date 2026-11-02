package com.zipick.notice.domain;

import com.zipick.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(
        name = "notice",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_notice_source_external_id",
                columnNames = {"source", "external_notice_id"}
        )
)
public class Notice extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notice_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agency_id")
    private Agency agency;

    @Column(name = "source", nullable = false, length = 30)
    private String source;

    @Column(name = "external_notice_id", nullable = false, length = 100)
    private String externalNoticeId;

    @Column(name = "previous_external_notice_id", length = 100)
    private String previousExternalNoticeId;

    @Column(name = "title", length = 500)
    private String title;

    @Column(name = "notice_type", length = 100)
    private String noticeType;

    @Column(name = "rental_type", length = 100)
    private String rentalType;

    @Column(name = "region", length = 100)
    private String region;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private NoticeStatus status;

    @Column(name = "raw_status", length = 100)
    private String rawStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_reason", length = 30)
    private NoticeStatusReason statusReason;

    @Column(name = "missing_count", nullable = false)
    private int missingCount;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "last_missing_checked_on")
    private LocalDate lastMissingCheckedOn;

    @Column(name = "announced_at")
    private LocalDate announcedAt;

    @Column(name = "winner_announced_at")
    private LocalDate winnerAnnouncedAt;

    @Column(name = "apply_start_at")
    private Instant applyStartAt;

    @Column(name = "apply_end_at")
    private Instant applyEndAt;

    @Column(name = "detail_url", length = 2000)
    private String detailUrl;

    @Column(name = "pc_url", length = 2000)
    private String pcUrl;

    @Column(name = "mobile_url", length = 2000)
    private String mobileUrl;

    @Column(name = "reference_text", columnDefinition = "text")
    private String reference;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", columnDefinition = "jsonb")
    private String rawPayload;

    @Column(name = "collected_at", nullable = false)
    private Instant collectedAt;

    protected Notice() {
    }

    public Notice(String source, String externalNoticeId, String title, NoticeStatus status) {
        this.source = source;
        this.externalNoticeId = externalNoticeId;
        this.title = title;
        this.status = status;
        this.missingCount = 0;
        this.collectedAt = Instant.now();
        this.lastSeenAt = this.collectedAt;
    }

    public void assignAgency(Agency agency) {
        this.agency = agency;
    }

    public void markSeen(Instant seenAt) {
        this.lastSeenAt = seenAt;
        this.missingCount = 0;
        this.lastMissingCheckedOn = null;
    }

    public boolean markMissing(LocalDate checkedOn, int cancellationThreshold) {
        if (status == NoticeStatus.CLOSED || status == NoticeStatus.CANCELLED
                || checkedOn.equals(lastMissingCheckedOn)) {
            return false;
        }

        this.lastMissingCheckedOn = checkedOn;
        this.missingCount++;
        if (missingCount >= cancellationThreshold) {
            changeStatus(NoticeStatus.CLOSED, NoticeStatusReason.SOURCE_MISSING);
            return true;
        }
        return false;
    }

    public void changeStatus(NoticeStatus status, NoticeStatusReason reason) {
        this.status = status;
        this.statusReason = reason;
    }

    /** 근거 검증을 통과한 AI 정규화 값만 선택적으로 반영합니다. */
    public void applyAiNormalizedDates(LocalDate winnerAnnouncedAt, Instant applyStartAt, Instant applyEndAt) {
        this.winnerAnnouncedAt = winnerAnnouncedAt == null ? this.winnerAnnouncedAt : winnerAnnouncedAt;
        this.applyStartAt = applyStartAt == null ? this.applyStartAt : applyStartAt;
        this.applyEndAt = applyEndAt == null ? this.applyEndAt : applyEndAt;
    }

    public void synchronize(
            Agency agency,
            String previousExternalNoticeId,
            String title,
            String noticeType,
            String rentalType,
            String region,
            NoticeStatus status,
            String rawStatus,
            NoticeStatusReason statusReason,
            LocalDate announcedAt,
            LocalDate winnerAnnouncedAt,
            Instant applyStartAt,
            Instant applyEndAt,
            String detailUrl,
            String pcUrl,
            String mobileUrl,
            String reference,
            String rawPayload,
            Instant collectedAt
    ) {
        this.agency = agency;
        this.previousExternalNoticeId = keepExistingWhenBlank(previousExternalNoticeId, this.previousExternalNoticeId);
        this.title = keepExistingWhenBlank(title, this.title);
        this.noticeType = keepExistingWhenBlank(noticeType, this.noticeType);
        this.rentalType = keepExistingWhenBlank(rentalType, this.rentalType);
        this.region = keepExistingWhenBlank(region, this.region);
        this.status = status;
        this.rawStatus = keepExistingWhenBlank(rawStatus, this.rawStatus);
        this.statusReason = statusReason;
        this.announcedAt = announcedAt == null ? this.announcedAt : announcedAt;
        this.winnerAnnouncedAt = winnerAnnouncedAt == null ? this.winnerAnnouncedAt : winnerAnnouncedAt;
        this.applyStartAt = applyStartAt == null ? this.applyStartAt : applyStartAt;
        this.applyEndAt = applyEndAt == null ? this.applyEndAt : applyEndAt;
        this.detailUrl = keepExistingWhenBlank(detailUrl, this.detailUrl);
        this.pcUrl = keepExistingWhenBlank(pcUrl, this.pcUrl);
        this.mobileUrl = keepExistingWhenBlank(mobileUrl, this.mobileUrl);
        this.reference = keepExistingWhenBlank(reference, this.reference);
        this.rawPayload = rawPayload;
        this.collectedAt = collectedAt;
        markSeen(collectedAt);
    }

    private String keepExistingWhenBlank(String newValue, String existingValue) {
        return newValue == null || newValue.isBlank() ? existingValue : newValue.trim();
    }

    public Long getId() {
        return id;
    }

    public Agency getAgency() {
        return agency;
    }

    public String getSource() {
        return source;
    }

    public String getExternalNoticeId() {
        return externalNoticeId;
    }

    public String getTitle() {
        return title;
    }

    public String getRegion() {
        return region;
    }

    public String getNoticeType() {
        return noticeType;
    }

    public String getRentalType() {
        return rentalType;
    }

    public NoticeStatus getStatus() {
        return status;
    }

    public NoticeStatusReason getStatusReason() {
        return statusReason;
    }

    public int getMissingCount() {
        return missingCount;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public LocalDate getLastMissingCheckedOn() {
        return lastMissingCheckedOn;
    }

    /**
     * 공고일. 같은 건물에 몇 주 간격으로 올라온 공고들이 제목·기관·유형까지 모두 같은 경우가 있어,
     * 지도에서 겹친 주택을 구분해 보여 줄 때 이 값이 유일한 구분자가 된다 (MapHousingSummaryResponse).
     */
    public LocalDate getAnnouncedAt() {
        return announcedAt;
    }

    public Instant getApplyStartAt() {
        return applyStartAt;
    }

    public Instant getApplyEndAt() {
        return applyEndAt;
    }

    public String getDetailUrl() {
        return detailUrl;
    }
}
