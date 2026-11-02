package com.zipick.notification.campaign;

import com.zipick.notification.client.BatchResponse;
import java.time.LocalDateTime;
import java.util.List;

/** 캠페인 + 알림센터 묶음 집계(예약된 것만). 알림센터에 닿지 않으면 집계는 null */
public record CampaignResponse(
        Long id,
        String title,
        String headline,
        String message,
        LocalDateTime scheduledAt,
        Campaign.Status status,
        int recipientCount,
        Progress progress,
        LocalDateTime createdAt
) {
    /** 묶음들의 합 */
    public record Progress(long total, long pending, long sent, long partial, long failed, long cancelled) {
        public static Progress sum(List<BatchResponse> batches) {
            long total = 0, pending = 0, sent = 0, partial = 0, failed = 0, cancelled = 0;
            for (BatchResponse b : batches) {
                total += b.total(); pending += b.pending(); sent += b.sent(); partial += b.partial(); failed += b.failed(); cancelled += b.cancelled();
            }
            return new Progress(total, pending, sent, partial, failed, cancelled);
        }
    }

    public static CampaignResponse from(Campaign c, Progress progress) {
        return new CampaignResponse(c.getId(), c.getTitle(), c.getHeadline(), c.getMessage(), c.getScheduledAt(), c.getStatus(),
                c.getRecipientCount(), progress, c.getCreatedAt());
    }
}
