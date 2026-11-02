package com.zipick.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 스케줄러가 어디까지 처리했는지 (예: 새 공고 역매칭의 마지막 확인 시각) */
@Entity
@Getter
@Table(name = "notification_cursors")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationCursor {

    public static final String NEW_MATCH = "new_match";

    @Id
    @Column(length = 50)
    private String name;

    @Column(name = "checked_at", nullable = false)
    private LocalDateTime checkedAt;

    public NotificationCursor(String name, LocalDateTime checkedAt) {
        this.name = name;
        this.checkedAt = checkedAt;
    }

    public void advance(LocalDateTime to) {
        this.checkedAt = to;
    }
}
