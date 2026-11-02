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
        List<NoticeHousingResponse> houses,
        List<NoticeAttachmentResponse> attachments,
        @com.fasterxml.jackson.annotation.JsonIgnore boolean preservesExistingHousings,
        @com.fasterxml.jackson.annotation.JsonIgnore List<ParseCheckpoint> parseCheckpoints
) {
    public NoticeResponse(String source, String noticeId, String statusName, String title,
                          String supplyInstitutionName, String previousNoticeId, String announcementDate,
                          String winnerAnnouncementDate, String applicationStartDate, String applicationEndDate,
                          String reference, String url, String pcUrl, String mobileUrl,
                          List<NoticeHousingResponse> houses, List<NoticeAttachmentResponse> attachments,
                          boolean preservesExistingHousings) {
        this(source, noticeId, statusName, title, supplyInstitutionName, previousNoticeId, announcementDate,
                winnerAnnouncementDate, applicationStartDate, applicationEndDate, reference, url, pcUrl,
                mobileUrl, houses, attachments, preservesExistingHousings, List.of());
    }
    public NoticeResponse(String source, String noticeId, String statusName, String title,
                          String supplyInstitutionName, String previousNoticeId, String announcementDate,
                          String winnerAnnouncementDate, String applicationStartDate, String applicationEndDate,
                          String reference, String url, String pcUrl, String mobileUrl,
                          List<NoticeHousingResponse> houses, List<NoticeAttachmentResponse> attachments) {
        this(source, noticeId, statusName, title, supplyInstitutionName, previousNoticeId, announcementDate,
                winnerAnnouncementDate, applicationStartDate, applicationEndDate, reference, url, pcUrl,
                mobileUrl, houses, attachments, false);
    }
    public NoticeResponse {
        houses = houses == null ? List.of() : List.copyOf(houses);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        parseCheckpoints = parseCheckpoints == null ? List.of() : List.copyOf(parseCheckpoints);
    }
}
