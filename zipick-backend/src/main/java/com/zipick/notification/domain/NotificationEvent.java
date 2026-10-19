package com.zipick.notification.domain;

import com.zipick.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 알림 아웃박스. 보낼 알림을 먼저 이 테이블에 기록하고, 스케줄러가 몇 분마다 읽어 보낸다.
 * dedupe_key 가 unique 라 같은 알림(같은 사용자·공고·D-day)은 두 번 만들어지지 않는다.
 * 실패하면 attempts 를 올리고 다음 주기에 다시 시도한다(최대 max-attempts).
 */
@Entity
@Getter
@Table(name = "notification_events",
        uniqueConstraints = @UniqueConstraint(name = "uk_notification_dedupe", columnNames = "dedupe_key"),
        indexes = @Index(name = "idx_notification_status", columnList = "status, scheduled_for"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationEvent extends BaseTimeEntity {

    public enum Type { DEADLINE, NEW_MATCH, TEST }
    public enum Status { PENDING, SENT, FAILED, SKIPPED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Type type;

    @Column(name = "notice_id")
    private Long noticeId;

    @Column(name = "dedupe_key", nullable = false, length = 120)
    private String dedupeKey;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(length = 500)
    private String url;

    @Column(name = "scheduled_for", nullable = false)
    private LocalDateTime scheduledFor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    /** 보낸 채널 (예: "slack,email") */
    @Column(length = 50)
    private String channels;

    @Builder
    public NotificationEvent(Long userId, Type type, Long noticeId, String dedupeKey, String title, String body, String url,
                             LocalDateTime scheduledFor) {
        this.userId = userId;
        this.type = type;
        this.noticeId = noticeId;
        this.dedupeKey = dedupeKey;
        this.title = title;
        this.body = body;
        this.url = url;
        this.scheduledFor = scheduledFor;
        this.status = Status.PENDING;
        this.attempts = 0;
    }

    public void markSent(String channels, LocalDateTime now) {
        this.status = Status.SENT;
        this.channels = channels;
        this.sentAt = now;
        this.attempts++;
        this.lastError = null;
    }

    public void markFailed(String error, int maxAttempts) {
        this.attempts++;
        this.lastError = error == null ? null : error.substring(0, Math.min(500, error.length()));
        if (this.attempts >= maxAttempts) this.status = Status.FAILED;
    }

    public void markSkipped(String why) {
        this.status = Status.SKIPPED;
        this.lastError = why;
        this.attempts++;
    }
}
