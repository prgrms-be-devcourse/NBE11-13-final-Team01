package com.zipick.notification.domain;

import com.zipick.global.entity.BaseTimeEntity;
import com.zipick.notification.client.NotificationRequest;
import com.zipick.notification.dto.NotificationSettingRequest;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자의 알림 설정. 메인 서버가 가진다 — 알림센터는 사용자를 모르고, 누구에게 보낼지는 메인 서버가 정해서 요청한다.
 * 광고성 정보(소식 메일)는 marketingConsent 가 true 인 사용자에게만, 동의 시각을 남긴다 (정보통신망법).
 */
@Entity
@Getter
@Table(name = "notification_settings")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationSetting extends BaseTimeEntity {

    public static final String DEFAULT_DEADLINE_DAYS = "3,1,0";

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "slack_webhook_url", length = 500)
    private String slackWebhookUrl;

    @Column(length = 255)
    private String email;

    @Column(name = "marketing_consent", nullable = false)
    private boolean marketingConsent;

    @Column(name = "marketing_consented_at")
    private LocalDateTime marketingConsentedAt;

    /** "3,1,0" — 접수 마감 며칠 전에 알릴지 */
    @Column(name = "deadline_days", nullable = false, length = 50)
    private String deadlineDays = DEFAULT_DEADLINE_DAYS;

    @Column(name = "new_match_enabled", nullable = false)
    private boolean newMatchEnabled = true;

    public static NotificationSetting defaults(Long userId) {
        NotificationSetting s = new NotificationSetting();
        s.userId = userId;
        return s;
    }

    /**
     * 화면에서 저장. 주소는 null=유지, ""=삭제. 광고 동의는 null=유지, 켜질 때 시각을 남기고 꺼지면 지운다.
     * 마감 알림 일수도 null=유지, ""=마감 알림 끔(D-3·D-1·D-day 를 모두 끈 경우).
     */
    public void apply(NotificationSettingRequest r, LocalDateTime now) {
        this.enabled = r.enabled();
        if (r.slackWebhookUrl() != null) this.slackWebhookUrl = blankToNull(r.slackWebhookUrl());
        if (r.email() != null) this.email = blankToNull(r.email());
        if (r.deadlineDays() != null) this.deadlineDays = normalizeDays(r.deadlineDays());
        this.newMatchEnabled = r.newMatchEnabled();
        if (r.marketingConsent() != null && r.marketingConsent() != this.marketingConsent) {
            this.marketingConsent = r.marketingConsent();
            this.marketingConsentedAt = r.marketingConsent() ? now : null;
        }
    }

    public boolean slackConfigured() { return slackWebhookUrl != null; }
    public boolean hasEmail() { return email != null; }

    /**
     * 주소가 있는 채널. 비어 있으면 보낼 곳이 없다.
     * 슬랙은 운영자만 쓴다(NotificationChannelPolicy). 일반 사용자는 웹훅이 남아 있어도 이메일로만 보낸다.
     */
    public List<String> channels(boolean slackAllowed) {
        List<String> out = new ArrayList<>(2);
        if (hasEmail()) out.add("email");
        if (slackAllowed && slackConfigured()) out.add("slack");
        return out;
    }

    /** 운영자가 아니게 된 계정의 웹훅을 지울 때 */
    public void clearSlack() { this.slackWebhookUrl = null; }

    public NotificationRequest.Recipient recipient() { return new NotificationRequest.Recipient(email, slackWebhookUrl); }

    /** 마감 며칠 전에 알릴지. 비어 있으면 마감 알림을 보내지 않는다 */
    public List<Integer> deadlineDayList() {
        return Arrays.stream(deadlineDays.split(",")).map(String::trim).filter(x -> !x.isEmpty()).map(Integer::parseInt).toList();
    }

    public boolean deadlineEnabled() { return !deadlineDayList().isEmpty(); }

    /** " 1, 3,0,3 " → "3,1,0" — 공백 제거, 중복 제거, 큰 수(이른 알림)부터 */
    static String normalizeDays(String raw) {
        return Arrays.stream(raw.split(",")).map(String::trim).filter(x -> !x.isEmpty())
                .map(Integer::parseInt).distinct().sorted(java.util.Comparator.reverseOrder())
                .map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    }

    public String slackWebhookMasked() {
        if (slackWebhookUrl == null) return null;
        return "…" + slackWebhookUrl.substring(Math.max(0, slackWebhookUrl.length() - 6));
    }

    private static String blankToNull(String s) {
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
