package com.zipick.notification.campaign;

import com.zipick.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 운영자가 보내는 소식 메일(광고성 캠페인). 메인 서버가 가진다 — 대상 선정(동의자)·발송 시각(야간 회피)은 메인 서버의 판단이다.
 * 예약하면 알림센터에 묶음 요청으로 넘기고, 묶음 id 만 기억해 집계·취소에 쓴다.
 *
 *   DRAFT ──schedule──▶ SCHEDULED ──cancel──▶ CANCELLED
 */
@Entity
@Getter
@Table(name = "campaigns")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Campaign extends BaseTimeEntity {

    public enum Status { DRAFT, SCHEDULED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 운영자용 이름 (받는 사람에게는 안 보임) */
    @Column(nullable = false, length = 100)
    private String title;

    /** 제목 줄 — 템플릿이 "(광고) " 를 앞에 붙인다 */
    @Column(nullable = false, length = 200)
    private String headline;

    @Column(nullable = false, columnDefinition = "text")
    private String message;

    @Column(name = "scheduled_at", nullable = false)
    private LocalDateTime scheduledAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.DRAFT;

    /** 알림센터 묶음 id. 채널 조합(이메일만/슬랙만/둘 다)마다 묶음이 하나라 쉼표로 여러 개 */
    @Column(name = "batch_ids", length = 100)
    private String batchIds;

    @Column(name = "recipient_count", nullable = false)
    private int recipientCount;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    public static Campaign draft(String title, String headline, String message, LocalDateTime scheduledAt, Long createdBy) {
        Campaign c = new Campaign();
        c.title = title;
        c.headline = headline;
        c.message = message;
        c.scheduledAt = scheduledAt;
        c.createdBy = createdBy;
        return c;
    }

    public void scheduled(List<Long> batchIds, int recipientCount, LocalDateTime at) {
        this.status = Status.SCHEDULED;
        this.batchIds = batchIds.isEmpty() ? null : String.join(",", batchIds.stream().map(String::valueOf).toList());
        this.recipientCount = recipientCount;
        this.scheduledAt = at;
    }

    public void cancelled() { this.status = Status.CANCELLED; }

    public List<Long> batchIdList() {
        if (batchIds == null || batchIds.isBlank()) return List.of();
        return Arrays.stream(batchIds.split(",")).map(Long::valueOf).toList();
    }
}
