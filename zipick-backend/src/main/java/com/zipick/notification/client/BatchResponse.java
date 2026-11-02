package com.zipick.notification.client;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 알림센터의 묶음 상태·집계. status 는 SCHEDULED | CANCELLED, 나머지는 메시지 수 */
public record BatchResponse(
        Long id,
        String source,
        String templateCode,
        List<String> channels,
        LocalDateTime scheduledAt,
        String status,
        Map<String, String> tags,
        long total,
        long pending,
        long sent,
        long partial,
        long failed,
        long cancelled,
        long skippedDuplicates,
        LocalDateTime createdAt
) {}
