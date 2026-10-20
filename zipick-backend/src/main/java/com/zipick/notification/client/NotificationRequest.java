package com.zipick.notification.client;

import java.time.LocalDateTime;
import java.util.Map;

/** 알림센터에 보내는 "알림 요청". 문구는 알림센터의 템플릿이 가진다 — 여기서는 코드와 변수만 */
public record NotificationRequest(
        String templateCode,
        Long userId,
        String email,
        String slackWebhookUrl,
        Map<String, Object> variables,
        String dedupeKey,
        LocalDateTime scheduledFor
) {
    public static NotificationRequest forUser(String templateCode, Long userId, Map<String, Object> variables, String dedupeKey) {
        return new NotificationRequest(templateCode, userId, null, null, variables, dedupeKey, null);
    }

    /** 가입 인증처럼 아직 계정이 없을 때: 이메일로 직접 */
    public static NotificationRequest toEmail(String templateCode, String email, Map<String, Object> variables) {
        return new NotificationRequest(templateCode, null, email, null, variables, null, null);
    }
}
