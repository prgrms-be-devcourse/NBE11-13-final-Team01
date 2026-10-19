package com.zipick.notification.dto;

import com.zipick.notification.domain.NotificationSetting;
import java.util.List;

/**
 * @param slackWebhookMasked 웹훅 URL 은 비밀값이라 끝 6자만 보여 준다
 * @param emailAvailable     서버에 SMTP 설정이 있어 이메일 채널을 쓸 수 있는지
 */
public record NotificationSettingResponse(
        boolean enabled,
        boolean slackConfigured,
        String slackWebhookMasked,
        String email,
        List<Integer> deadlineDays,
        boolean newMatchEnabled,
        boolean emailAvailable
) {
    public static NotificationSettingResponse from(NotificationSetting s, boolean emailAvailable) {
        String url = s.getSlackWebhookUrl();
        String masked = url == null ? null : "…" + url.substring(Math.max(0, url.length() - 6));
        return new NotificationSettingResponse(s.isEnabled(), s.hasSlack(), masked, s.getEmail(), s.deadlineDayList(),
                s.isNewMatchEnabled(), emailAvailable);
    }

    public static NotificationSettingResponse empty(boolean emailAvailable) {
        return new NotificationSettingResponse(false, false, null, null, List.of(3, 1, 0), true, emailAvailable);
    }
}
