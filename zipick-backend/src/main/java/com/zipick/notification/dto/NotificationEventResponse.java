package com.zipick.notification.dto;

import java.time.LocalDateTime;

/** 알림센터의 MessageResponse 그대로. 프론트는 templateCode·category·status·title 을 쓴다 */
public record NotificationEventResponse(
        Long id,
        Long userId,
        String templateCode,
        String category,
        Long campaignId,
        String title,
        String body,
        String url,
        String status,
        int attempts,
        String lastError,
        String channels,
        LocalDateTime scheduledFor,
        LocalDateTime sentAt,
        boolean duplicate
) {}
