package com.zipick.notification.domain;

import com.zipick.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Arrays;
import java.util.List;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자별 알림 설정. 채널은 슬랙 웹훅(URL 하나)과 이메일(SMTP 설정이 있을 때만).
 * 웹훅 URL 은 hooks.slack.com 만 허용한다(임의 주소로 요청을 보내지 않게).
 */
@Entity
@Getter
@Table(name = "notification_settings")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationSetting extends BaseTimeEntity {

    public static final String SLACK_WEBHOOK_PREFIX = "https://hooks.slack.com/";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "slack_webhook_url", length = 500)
    private String slackWebhookUrl;

    @Column(length = 255)
    private String email;

    /** 접수 마감 며칠 전에 알릴지. 쉼표 구분 (예: "3,1,0") */
    @Column(name = "deadline_days", nullable = false, length = 50)
    private String deadlineDays;

    /** 내 조건에 맞는 새 공고가 들어오면 알림 */
    @Column(name = "new_match_enabled", nullable = false)
    private boolean newMatchEnabled;

    @Builder
    public NotificationSetting(Long userId, boolean enabled, String slackWebhookUrl, String email, String deadlineDays,
                               boolean newMatchEnabled) {
        this.userId = userId;
        update(enabled, slackWebhookUrl, email, deadlineDays, newMatchEnabled);
    }

    public void update(boolean enabled, String slackWebhookUrl, String email, String deadlineDays, boolean newMatchEnabled) {
        this.enabled = enabled;
        // null = 그대로 두기(화면에서 안 바꿈), "" = 지우기
        if (slackWebhookUrl != null) this.slackWebhookUrl = blankToNull(slackWebhookUrl);
        if (email != null) this.email = blankToNull(email);
        this.deadlineDays = deadlineDays == null || deadlineDays.isBlank() ? "3,1,0" : deadlineDays;
        this.newMatchEnabled = newMatchEnabled;
    }

    public List<Integer> deadlineDayList() {
        return Arrays.stream(deadlineDays.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(Integer::parseInt).distinct().sorted(java.util.Comparator.reverseOrder()).toList();
    }

    public boolean hasSlack() { return slackWebhookUrl != null; }
    public boolean hasEmail() { return email != null; }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
