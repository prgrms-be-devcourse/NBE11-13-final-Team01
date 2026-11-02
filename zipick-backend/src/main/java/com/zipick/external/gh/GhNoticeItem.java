package com.zipick.external.gh;

import java.time.LocalDate;

record GhNoticeItem(
        String noticeId,
        String title,
        String housingType,
        String region,
        LocalDate announcedAt,
        LocalDate applyEndAt,
        String status,
        String detailUrl
) {
}
