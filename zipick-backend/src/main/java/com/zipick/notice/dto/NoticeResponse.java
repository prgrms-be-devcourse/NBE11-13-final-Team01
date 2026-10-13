package com.zipick.notice.dto;

import java.util.List;

public record NoticeResponse(
        String source,
        String noticeId,
        String statusName,
        String title,
        String supplyInstitutionName,
        String previousNoticeId,
        String announcementDate,
        String winnerAnnouncementDate,
        String applicationStartDate,
        String applicationEndDate,
        String reference,
        String url,
        String pcUrl,
        String mobileUrl,
        List<NoticeHousingResponse> houses
) {
    public NoticeResponse {
        houses = houses == null ? List.of() : List.copyOf(houses);
    }
}
