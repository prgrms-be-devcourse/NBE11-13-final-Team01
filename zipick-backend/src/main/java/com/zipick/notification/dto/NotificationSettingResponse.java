package com.zipick.notification.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 알림센터의 SubscriptionResponse. 웹훅은 끝 6자만 온다 */
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
        boolean emailAvailable
) {}
