package com.zipick.notification.dto;

import com.zipick.notification.domain.NotificationEvent;
import java.time.LocalDateTime;

public record NotificationEventResponse(
        Long id,
        NotificationEvent.Type type,
        Long noticeId,
        String title,
        String body,
        String url,
        NotificationEvent.Status status,
        int attempts,
        String lastError,
        String channels,
        LocalDateTime scheduledFor,
        LocalDateTime sentAt
) {
    public static NotificationEventResponse from(NotificationEvent e) {
        return new NotificationEventResponse(e.getId(), e.getType(), e.getNoticeId(), e.getTitle(), e.getBody(), e.getUrl(),
                e.getStatus(), e.getAttempts(), e.getLastError(), e.getChannels(), e.getScheduledFor(), e.getSentAt());
    }
}
