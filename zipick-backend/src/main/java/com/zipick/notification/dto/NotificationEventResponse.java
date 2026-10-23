package com.zipick.notification.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 알림센터의 메시지 응답(v1) 그대로. 채널별 결과는 deliveries 에.
 * status: PENDING | SENT | PARTIAL(일부 채널만 성공) | FAILED | CANCELLED
 */
public record NotificationEventResponse(
        Long id,
        String source,
        String templateCode,
        Long batchId,
        List<String> channels,
        String title,
        String status,
        List<Delivery> deliveries,
        List<String> missingVariables,
        Map<String, String> tags,
        LocalDateTime scheduledAt,
        LocalDateTime createdAt,
        boolean duplicate
) {
    public record Delivery(String channel, String status, int attempts, String lastError, LocalDateTime sentAt) {}
}
