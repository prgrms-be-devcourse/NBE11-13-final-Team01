package com.zipick.notification.dto;

import com.zipick.notification.domain.NotificationSetting;
import java.time.LocalDateTime;
import java.util.List;

/** 알림 설정 화면. 웹훅은 비밀값이라 끝 6자만 */
public record NotificationSettingResponse(
        Long userId,
        boolean enabled,
        boolean slackConfigured,
        String slackWebhookMasked,
        String email,
        boolean marketingConsent,
        LocalDateTime marketingConsentedAt,
        List<Integer> deadlineDays,
        boolean newMatchEnabled,
        /** 알림센터에 이메일 발송 설정(SMTP/SES)이 있는지. 없으면 화면에 안내 */
        boolean emailAvailable
) {
    public static NotificationSettingResponse from(NotificationSetting s, boolean emailAvailable) {
        return new NotificationSettingResponse(s.getUserId(), s.isEnabled(), s.slackConfigured(), s.slackWebhookMasked(), s.getEmail(),
                s.isMarketingConsent(), s.getMarketingConsentedAt(), s.deadlineDayList(), s.isNewMatchEnabled(), emailAvailable);
    }
}
